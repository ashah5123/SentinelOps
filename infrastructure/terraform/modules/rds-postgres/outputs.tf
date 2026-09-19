output "endpoint" {
  value = aws_db_instance.this.address
}

output "port" {
  value = aws_db_instance.this.port
}

output "secret_arn" {
  value       = aws_secretsmanager_secret.postgres.arn
  description = "ARN of the Secrets Manager secret holding connection credentials — read this at deploy time, never the password itself from Terraform output."
}
