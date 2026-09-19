variable "repository_names" {
  type    = list(string)
  default = ["incident-service", "telemetry-correlation-service"]
}

variable "image_tag_mutability" {
  type    = string
  default = "IMMUTABLE"
}

variable "untagged_image_expiry_days" {
  type    = number
  default = 14
}

variable "tags" {
  type    = map(string)
  default = {}
}
