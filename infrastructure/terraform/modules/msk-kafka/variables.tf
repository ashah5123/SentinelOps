variable "name" {
  type = string
}

variable "vpc_id" {
  type = string
}

variable "private_subnet_ids" {
  type        = list(string)
  description = "Provide 2 or 3 subnets across distinct AZs — MSK requires at least 2 broker nodes across distinct AZs."
}

variable "allowed_security_group_ids" {
  type = list(string)
}

variable "kafka_version" {
  type    = string
  default = "3.7.x"
}

variable "broker_instance_type" {
  type        = string
  default     = "kafka.t3.small"
  description = "Low-cost default for dev; use e.g. kafka.m5.large for production."
}

variable "broker_count" {
  type        = number
  default     = 2
  description = "Must be a multiple of the number of subnets provided."
}

variable "broker_ebs_volume_gb" {
  type    = number
  default = 100
}

variable "tags" {
  type    = map(string)
  default = {}
}
