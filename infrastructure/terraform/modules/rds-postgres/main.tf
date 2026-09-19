# Managed PostgreSQL (pgvector-capable via RDS's postgresql engine + the pgvector extension,
# which is available as of PostgreSQL 15+ on RDS — enable it post-provisioning with
# `CREATE EXTENSION vector;`, the same statement services/incident-service's own Flyway
# migrations already run locally). Never publicly accessible; encrypted at rest with a
# dedicated KMS key and in transit by requiring the rds.force_ssl parameter.

resource "random_password" "master" {
  length  = 32
  special = false # avoids characters that need extra shell/URL escaping in connection strings
}

resource "aws_kms_key" "rds" {
  description             = "${var.name} RDS encryption"
  deletion_window_in_days = 30
  enable_key_rotation     = true
  tags                    = var.tags
}

resource "aws_db_subnet_group" "this" {
  name       = "${var.name}-postgres"
  subnet_ids = var.private_subnet_ids
  tags       = var.tags
}

resource "aws_security_group" "postgres" {
  name_prefix = "${var.name}-postgres-"
  vpc_id      = var.vpc_id

  ingress {
    from_port       = 5432
    to_port         = 5432
    protocol        = "tcp"
    security_groups = var.allowed_security_group_ids
  }
  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
  tags = merge(var.tags, { Name = "${var.name}-postgres-sg" })
}

resource "aws_db_parameter_group" "postgres" {
  name   = "${var.name}-postgres"
  family = "postgres16"

  parameter {
    name  = "rds.force_ssl"
    value = "1"
  }
  tags = var.tags
}

resource "aws_db_instance" "this" {
  identifier     = var.name
  engine         = "postgres"
  engine_version = var.engine_version
  instance_class = var.instance_class

  allocated_storage     = var.allocated_storage_gb
  max_allocated_storage = var.allocated_storage_gb * 4 # storage autoscaling ceiling
  storage_type          = "gp3"
  storage_encrypted     = true
  kms_key_id            = aws_kms_key.rds.arn

  db_name  = var.database_name
  username = var.master_username
  password = random_password.master.result
  port     = 5432

  db_subnet_group_name   = aws_db_subnet_group.this.name
  vpc_security_group_ids = [aws_security_group.postgres.id]
  parameter_group_name   = aws_db_parameter_group.postgres.name
  publicly_accessible    = false

  multi_az                = var.multi_az
  backup_retention_period = var.backup_retention_days
  backup_window           = "03:00-04:00"
  maintenance_window      = "mon:04:30-mon:05:30"
  deletion_protection     = var.deletion_protection
  skip_final_snapshot     = false
  final_snapshot_identifier = "${var.name}-final"

  enabled_cloudwatch_logs_exports = ["postgresql", "upgrade"]

  tags = var.tags
}

# Stored in AWS Secrets Manager, never in Terraform state output or a committed file — the
# application reads it at deploy time via an external-secrets operator or CI/CD injection (see
# docs/development/production-deployment.md), never via `terraform output`.
resource "aws_secretsmanager_secret" "postgres" {
  name       = "${var.name}-postgres-credentials"
  kms_key_id = aws_kms_key.rds.id
  tags       = var.tags
}

resource "aws_secretsmanager_secret_version" "postgres" {
  secret_id = aws_secretsmanager_secret.postgres.id
  secret_string = jsonencode({
    username = var.master_username
    password = random_password.master.result
    host     = aws_db_instance.this.address
    port     = 5432
    dbname   = var.database_name
  })
}
