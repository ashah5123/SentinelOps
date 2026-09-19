variable "name" {
  type = string
}

variable "oidc_provider_arn" {
  type        = string
  description = "From the eks module's output — required to trust the cluster's service accounts."
}

variable "oidc_provider_url" {
  type        = string
  description = "Issuer URL without the https:// prefix is derived internally; pass the full issuer URL from the eks module's output."
}

variable "namespace" {
  type    = string
  default = "sentinelops"
}

variable "service_account_name" {
  type    = string
  default = "sentinelops-app"
}

variable "secret_arns" {
  type        = list(string)
  description = "Secrets Manager ARNs the application role may read (database/redis credentials, etc.) — never a wildcard."
}

variable "s3_bucket_arns" {
  type    = list(string)
  default = []
}

variable "tags" {
  type    = map(string)
  default = {}
}
