variable "name" {
  type = string
}

variable "monthly_budget_usd" {
  type        = number
  description = "Monthly budget ceiling in USD. Set deliberately low for dev to catch runaway spend early."
}

variable "notification_emails" {
  type        = list(string)
  description = "Real distribution list / on-call email — never a placeholder in an actual deployment."
}

variable "alert_thresholds_percent" {
  type    = list(number)
  default = [50, 80, 100]
}

variable "tags" {
  type    = map(string)
  default = {}
}
