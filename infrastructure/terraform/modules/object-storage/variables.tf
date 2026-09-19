variable "name" {
  type        = string
  description = "Base name; each bucket is named \"<name>-<purpose>\", e.g. \"sentinelops-production-runbooks\"."
}

variable "buckets" {
  type        = list(string)
  default     = ["runbooks", "incident-artifacts", "postmortems"]
  description = "Matches infrastructure/docker/minio/init-buckets.sh's bucket set exactly, so local (MinIO) and production (S3) present the same three logical buckets to the application."
}

variable "noncurrent_version_expiration_days" {
  type    = number
  default = 90
}

variable "tags" {
  type    = map(string)
  default = {}
}
