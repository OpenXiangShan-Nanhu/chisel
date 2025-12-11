package chisel3
case object BuildInfo {
  val buildInfoPackage: String = "chisel3"
  val version: String = "7.5.0"
  val scalaVersion: String = "2.13.18"
  val firtoolVersion: scala.Option[String] = Some("1.138.0")
  override val toString: String = {
    "buildInfoPackage: %s, version: %s, scalaVersion: %s, firtoolVersion %s".format(
        buildInfoPackage, version, scalaVersion, firtoolVersion
    )
  }
}