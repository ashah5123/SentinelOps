# Cost control: an AWS Budget scoped to resources carrying this deployment's tags (see the
# root module's common `tags` variable — every module in this repository is called with the
# same tag set, so this budget's cost filter and the resource tags always stay in sync), with
# alert thresholds at 50/80/100% of the monthly ceiling.

resource "aws_budgets_budget" "monthly" {
  name         = "${var.name}-monthly"
  budget_type  = "COST"
  limit_amount = tostring(var.monthly_budget_usd)
  limit_unit   = "USD"
  time_unit    = "MONTHLY"

  cost_filter {
    name   = "TagKeyValue"
    values = [for k, v in var.tags : "user:${k}$${v}"]
  }

  dynamic "notification" {
    for_each = var.alert_thresholds_percent
    content {
      comparison_operator        = "GREATER_THAN"
      threshold                  = notification.value
      threshold_type             = "PERCENTAGE"
      notification_type          = "ACTUAL"
      subscriber_email_addresses = var.notification_emails
    }
  }
}
