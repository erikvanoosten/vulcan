package vulcan.internal

import scala.compiletime.ops

object singletons {
  type >[A <: Int, B <: Int] = ops.int.>[A, B] =:= true
  type >=[A <: Int, B <: Int] = ops.int.>=[A, B] =:= true
  type <=[A <: Int, B <: Int] = ops.int.<=[A, B] =:= true
}
