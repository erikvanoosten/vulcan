package vulcan

import _root_.scodec.bits.{BitVector, ByteVector}
import _root_.scodec.interop.cats._
import _root_.scodec.{
  Attempt,
  DecodeResult,
  Decoder,
  Encoder,
  Err,
  SizeBound,
  codecs,
  Codec => Scodec
}
import cats.data.Chain
import cats.syntax.all._
import org.apache.avro.Schema
import org.apache.avro.generic.{GenericData, GenericRecord}
import org.apache.avro.util.Utf8
import vulcan.internal.converters.collection._

import java.nio.{ByteBuffer, ByteOrder}
import java.util
import scala.annotation.tailrec

package object scodec {
  lazy val intScodec: Scodec[java.lang.Integer] =
    ZigZagVarIntCodec.xmap(java.lang.Integer.valueOf(_), _.intValue)

  private val zigZagVarLong: Scodec[Long] = new VarLongCodec().xmap(
    zz => (zz >>> 1L) ^ -(zz & 1L),
    i => (i << 1L) ^ (i >> 63L)
  )

  val longScodec: Scodec[java.lang.Long] =
    zigZagVarLong.xmap(java.lang.Long.valueOf(_), _.longValue)

  val floatScodec: Scodec[java.lang.Float] =
    codecs.floatL.xmap(java.lang.Float.valueOf(_), _.floatValue)

  val doubleScodec: Scodec[java.lang.Double] =
    codecs.doubleL.xmap(java.lang.Double.valueOf(_), _.doubleValue)

  val stringScodec: Scodec[Utf8] =
    codecs
      .variableSizeBytesLong(zigZagVarLong, codecs.bytes)
      .xmap(bytes => new Utf8(bytes.toArray), utf8 => ByteVector(utf8.getBytes))

  val boolScodec: Scodec[java.lang.Boolean] =
    codecs.bool.xmap(java.lang.Boolean.valueOf(_), _.booleanValue)

  def recordEncoder(writerSchema: Schema): Encoder[GenericRecord] =
    Encoder { record =>
      writerSchema.getFields.asScala.toList.zipWithIndex
        .map { case (field, idx) => (field.schema, record.get(idx)) }
        .traverse {
          case (schema, value) =>
            forWriterSchema(schema).encode(value)
        }
        .map(_.reduce(_ ++ _))
    }

  def recordDecoder(writerSchema: Schema): Decoder[GenericRecord] =
    Decoder { bytes =>
      writerSchema.getFields.asScala.toList
        .map(field => forWriterSchema(field.schema).map((field.name, _)))
        .scanLeft[Attempt[DecodeResult[(String, Any)]]](
          Attempt.successful(DecodeResult((null, null), bytes))
        ) { (prev, codec) =>
          prev
            .map(_.remainder)
            .flatMap(codec.decode)
        }
        .sequence
        .map { l =>
          val record = new GenericData.Record(writerSchema)
          l.tail.foreach {
            case DecodeResult((name, value), _) => record.put(name, value)
          }
          DecodeResult(record, l.last.remainder)
        }
    }

  val nullScodec: Scodec[Null] =
    codecs.ignore(0).xmap(_ => null, _ => ())

  def blockScodec[A](codec: Scodec[A]): Scodec[List[A]] =
    codecs.listOfN(ZigZagVarIntCodec, codec)

  def arrayEncoder[A](codec: Scodec[A]): Encoder[java.util.List[A]] =
    new Encoder[java.util.List[A]] {
      override def encode(value: util.List[A]): Attempt[BitVector] = {
        intScodec.encode(value.size).flatMap { bits =>
          codecs
            .list(codec)
            .encode(value.asScala.toList)
            .map(bits ++ _)
            .flatMap(
              bits =>
                (if (value.size != 0) intScodec.encode(0)
                 else Attempt.successful(BitVector.empty)).map(bits ++ _)
            )
        }
      }

      override def sizeBound: SizeBound = SizeBound.atLeast(1L)
    }

  def arrayDecoder[A](codec: Scodec[A]): Decoder[java.util.List[A]] = Decoder { bits =>
    def decodeBlock(bits: BitVector) = blockScodec(codec).decode(bits)

    def loop(remaining: BitVector, acc: Chain[A]): Attempt[DecodeResult[Chain[A]]] =
      decodeBlock(remaining).flatMap {
        case DecodeResult(value, remainder) =>
          if (value.isEmpty) Attempt.successful(DecodeResult(acc, remainder))
          else loop(remainder, acc ++ Chain.fromSeq(value))
      }

    loop(bits, Chain.empty).map(_.map(_.toList.asJava))
  }

  def forWriterSchema(schema: Schema): Scodec[Any] = schema.getType match {
    case Schema.Type.FLOAT =>
      floatScodec.widen[Any](identity, {
        case i: java.lang.Float => Attempt.successful(i)
        case other              => Attempt.failure(Err(s"$other is not a Float"))
      })
    case Schema.Type.DOUBLE =>
      doubleScodec.widen[Any](identity, {
        case d: java.lang.Double => Attempt.successful(d)
        case other               => Attempt.failure(Err(s"$other is not a Double"))
      })
    case Schema.Type.INT =>
      intScodec.widen[Any](identity, {
        case d: java.lang.Integer => Attempt.successful(d)
        case other                => Attempt.failure(Err(s"$other is not a int"))
      })
    case Schema.Type.LONG =>
      longScodec.widen[Any](identity, {
        case d: java.lang.Long => Attempt.successful(d)
        case other             => Attempt.failure(Err(s"$other is not a Long"))
      })
    case Schema.Type.RECORD =>
      Scodec[GenericRecord](recordEncoder(schema), recordDecoder(schema))
        .widen[Any](identity, {
          case d: GenericRecord => Attempt.successful(d)
          case other            => Attempt.failure(Err(s"$other is not a GenericRecord"))
        })
    case Schema.Type.ARRAY =>
      val elementCodec = forWriterSchema(schema.getElementType)
      Scodec[java.util.List[Any]](arrayEncoder(elementCodec), arrayDecoder(elementCodec))
        .widen[Any](
          identity, {
            case d: java.util.List[_] => Attempt.successful(d.asInstanceOf[java.util.List[Any]])
            case other                => Attempt.failure(Err(s"$other is not a java.util.List"))
          }
        )
    case Schema.Type.STRING =>
      stringScodec.widen[Any](identity, {
        case d: Utf8 => Attempt.successful(d)
        case other   => Attempt.failure(Err(s"$other is not a Utf8"))
      })
    case Schema.Type.NULL =>
      nullScodec.widen[Any](identity, { n =>
        if (n == null) Attempt.successful(null) else Attempt.failure(Err(s"$n is not null"))
      })
    case _ => ???
  }

}

object ZigZagVarIntCodec extends Scodec[Int] {
  private val toPositiveLong = (i: Int) => {
    (i.toLong << 1) ^ (i.toLong >> 31)
  }

  private val fromPositiveLong = (l: Long) => {
    val i = l.toInt
    (i >>> 1) ^ -(i & 1)
  }

  private[this] val long =
    new VarLongCodec()
      .xmap(ZigZagVarIntCodec.fromPositiveLong, ZigZagVarIntCodec.toPositiveLong)

  override def sizeBound =
    SizeBound.bounded(1L, 5L)

  override def encode(i: Int) =
    long.encode(i)

  override def decode(buffer: BitVector) =
    long.decode(buffer)

  override def toString = "variable-length integer"

}
final class VarLongCodec extends Scodec[Long] {
  import VarLongCodec._

  override def sizeBound = SizeBound.bounded(1L, 11L)

  override def encode(i: Long) = {
    val buffer = ByteBuffer.allocate(11).order(ByteOrder.BIG_ENDIAN)
    val encoder = BEEncoder
    val written = encoder(i, buffer)
    buffer.flip()
    val relevantBits = BitVector.view(buffer).take(written.toLong)
    Attempt.successful(relevantBits)
  }

  override def decode(buffer: BitVector) = {
    BEDecoder(buffer)
  }

  override def toString = "variable-length integer"

  private val BEEncoder = (value: Long, buffer: ByteBuffer) => runEncodingBE(value, buffer, 8)
  @tailrec
  private def runEncodingBE(value: Long, buffer: ByteBuffer, size: Int): Int =
    if ((value & MoreBytesMask) != 0) {
      buffer.put((value & RelevantDataBits | MostSignificantBit).toByte)
      runEncodingBE(value >>> BitsPerByte, buffer, size + 8)
    } else {
      buffer.put(value.toByte)
      size
    }

  private val BEDecoder = (buffer: BitVector) =>
    runDecodingBE(buffer, MostSignificantBit.toByte, 0L, 0)

  @tailrec
  private def runDecodingBE(
    buffer: BitVector,
    byte: Byte,
    value: Long,
    shift: Int
  ): Attempt[DecodeResult[Long]] =
    if ((byte & MostSignificantBit) != 0) {
      if (buffer.sizeLessThan(8L)) {
        Attempt.failure(Err.InsufficientBits(8L, buffer.size, Nil))
      } else {
        val nextByte = buffer.take(8L).toByte(false)
        val nextValue = value | (nextByte & RelevantDataBits) << shift
        runDecodingBE(buffer.drop(8L), nextByte, nextValue, shift + BitsPerByte)
      }
    } else {
      Attempt.successful(DecodeResult(value, buffer))
    }

}
object VarLongCodec {
  private val RelevantDataBits = 0x7FL
  private val MoreBytesMask = ~RelevantDataBits.toInt
  private val MostSignificantBit = 0x80
  private val BitsPerByte = 7
}