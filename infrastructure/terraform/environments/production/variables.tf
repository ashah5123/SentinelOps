variable "aws_region" {
  type    = string
  default = "us-east-1"
}

variable "azs" {
  type    = list(string)
  default = ["us-east-1a", "us-east-1b", "us-east-1c"]
}

variable "owner_tag" {
  type    = string
  default = "platform-team"
}

variable "cost_center_tag" {
  type        = string
  description = "Real cost-center/billing code — replace the example placeholder before applying."
  default     = "CC-0000"
}

variable "budget_alert_emails" {
  type        = list(string)
  description = "Real on-call/finance distribution list — replace the example placeholder before applying."
  default     = ["platform-team@example.com", "finance@example.com"]
}
