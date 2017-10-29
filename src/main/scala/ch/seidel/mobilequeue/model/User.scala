package ch.seidel.mobilequeue.model

import io.swagger.annotations.{ApiModel, ApiModelProperty}
import scala.annotation.meta.field

@ApiModel(description = "A user object")
final case class User
(
  @(ApiModelProperty @field)(value = "unique identifier for the user")
  id: Long,
  @(ApiModelProperty @field)(value = "unique identifier for the user's devices")
  deviceIds: Seq[String],
  @(ApiModelProperty @field)(value = "name of the user")
  name: String,
  @(ApiModelProperty @field)(value = "password of the user")
  password: String,
  @(ApiModelProperty @field)(value = "email of the user")
  mail: String,
  @(ApiModelProperty @field)(value = "mobile-phone of the user")
  mobile: String
  ) {
  def withHiddenPassword = copy(password = "***")
  def withHiddenDeviceIds = copy(deviceIds = Seq.empty)
}

object UserDefaults {
  def empty(name: String): User = User(0, Seq.empty, name, "***", "", "")
  def empty(id: Long): User = User(id, Seq.empty, "not existing!", "***", "", "")
}
//object User {
//  def apply(id: Long, deviceId: String, name: String): User = User(id, Seq(deviceId), name, "", "", "")
//}

@ApiModel(description = "A List of user object")
final case class Users(users: Seq[User])
