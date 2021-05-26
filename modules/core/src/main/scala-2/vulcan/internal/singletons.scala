package vulcan.internal

object singletons {
  type >[A, B] = singleton.ops.Require[singleton.ops.>[A, B]]
  type >=[A, B] = singleton.ops.Require[singleton.ops.>=[A, B]]
  type <=[A, B] = singleton.ops.Require[singleton.ops.<=[A, B]]
}
