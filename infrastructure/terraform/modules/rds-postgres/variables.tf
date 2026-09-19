variable "name" {
  type = string
}

variable "vpc_id" {
  type = string
}

variable "private_subnet_ids" {
  type = list(string)
}

variable "allowed_security_group_ids" {
  type        = list(string)
  description = "Security groups (typically the EKS node/pod security group) permitted to reach PostgreSQL on port 5432. Never opened to a CIDR block."
}

variable "engine_version" {
  type    = string
  default = "16.4"
}

variable "instance_class" {
  type        = string
  default     = "db.t4g.medium"
  description = "Low-cost default for dev; use e.g. db.r6g.large or larger for production."
}

variable "allocated_storage_gb" {
  type    = number
  default = 50
}

variable "multi_az" {
  type        = bool
  default     = true
  description = "Set to false only for a low-cost dev environment — production must stay Multi-AZ."
}

variable "backup_retention_days" {
  type    = number
  default = 14
}

variable "deletion_protection" {
  type    = bool
  default = true
}

variable "database_name" {
  type    = string
  default = "sentinelops"
}

variable "master_username" {
  type    = string
  default = "sentinelops_admin"
}

variable "tags" {
  type    = map(string)
  default = {}
}
