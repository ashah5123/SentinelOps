variable "aws_region" {
  type    = string
  default = "us-east-1"
}

variable "azs" {
  type    = list(string)
  default = ["us-east-1a", "us-east-1b"]
}

variable "owner_tag" {
  type        = string
  description = "Team or individual accountable for this environment's cost/operation — see docs/development/operational-ownership.md."
  default     = "platform-team"
}

variable "budget_alert_emails" {
  type        = list(string)
  description = "Real distribution list — replace the example placeholder before applying."
  default     = ["platform-team@example.com"]
}
