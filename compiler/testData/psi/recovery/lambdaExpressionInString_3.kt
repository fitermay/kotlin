fun f1(p1: Int, p2: Int, f: (Int, Int) -> String): String = f(p1, p2)
val vF1: (Int, Int) -> String = {a, b -> "${"}
val vF2: (Int, Int) -> String = {_, b -> "Only b = $b"}
fun main(args: Array<String>) {
  f1(1,2, vF1)
}