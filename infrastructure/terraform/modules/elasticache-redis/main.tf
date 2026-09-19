# Managed Redis. Encrypted at rest and in transit; never publicly accessible. This project's
# application code does not yet read/write Redis (see docs/development/local-platform.md — the
# local Compose "redis" service is provisioned but unused pending a future caching feature) —
# this module exists so the production topology matches the documented local stack and is ready
# the moment that feature lands, not because anything currently depends on it.

resource "aws_elasticache_subnet_group" "this" {
  name       = "${var.name}-redis"
  subnet_ids = var.private_subnet_ids
  tags       = var.tags
}

resource "aws_security_group" "redis" {
  name_prefix = "${var.name}-redis-"
  vpc_id      = var.vpc_id

  ingress {
    from_port       = 6379
    to_port         = 6379
    protocol        = "tcp"
    security_groups = var.allowed_security_group_ids
  }
  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
  tags = merge(var.tags, { Name = "${var.name}-redis-sg" })
}

resource "random_password" "auth_token" {
  length  = 32
  special = false
}

resource "aws_elasticache_replication_group" "this" {
  replication_group_id = var.name
  description           = "${var.name} Redis"
  engine                = "redis"
  engine_version        = var.engine_version
  node_type             = var.node_type
  num_cache_clusters    = var.num_cache_nodes
  port                  = 6379

  subnet_group_name = aws_elasticache_subnet_group.this.name
  security_group_ids = [aws_security_group.redis.id]

  at_rest_encryption_enabled = true
  transit_encryption_enabled = true
  auth_token                 = random_password.auth_token.result

  automatic_failover_enabled = var.num_cache_nodes > 1
  multi_az_enabled           = var.num_cache_nodes > 1

  snapshot_retention_limit = 7
  snapshot_window          = "03:00-05:00"
  maintenance_window       = "mon:05:00-mon:06:00"

  tags = var.tags
}

resource "aws_secretsmanager_secret" "redis" {
  name = "${var.name}-redis-credentials"
  tags = var.tags
}

resource "aws_secretsmanager_secret_version" "redis" {
  secret_id = aws_secretsmanager_secret.redis.id
  secret_string = jsonencode({
    host       = aws_elasticache_replication_group.this.primary_endpoint_address
    port       = 6379
    auth_token = random_password.auth_token.result
  })
}
