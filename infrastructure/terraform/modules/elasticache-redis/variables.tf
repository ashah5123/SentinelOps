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
  type = list(string)
}

variable "node_type" {
  type        = string
  default     = "cache.t4g.micro"
  description = "Low-cost default for dev; use e.g. cache.r6g.large for production."
}

variable "num_cache_nodes" {
  type        = number
  default     = 1
  description = "1 for dev (no replication); use a replication group with >= 2 nodes for production."
}

variable "engine_version" {
  type    = string
  default = "7.1"
}

variable "tags" {
  type    = map(string)
  default = {}
}
