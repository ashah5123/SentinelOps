# Kafka-compatible event streaming: Amazon MSK, Kafka-API-compatible with the Redpanda broker
# this project already uses locally (infrastructure/docker/docker-compose.yml's "redpanda"
# service) — the application's Kafka client configuration (bootstrap-servers, topic names,
# consumer groups) needs no code change to point at MSK instead, since it speaks the standard
# Kafka wire protocol either way.
#
# DOCUMENTED ALTERNATIVE: a team that wants to keep the exact same Redpanda binary in
# production (identical behavior to local dev, simpler operationally, no separate AWS managed-
# streaming bill) can instead self-host Redpanda on the EKS cluster this repository's ../eks
# module provisions, using Redpanda's own Helm chart (https://charts.redpanda.com) against 3
# nodes with local NVMe-backed PersistentVolumes. That path is not implemented as a Terraform
# module here — it is a Helm/Kubernetes concern, not an AWS-managed-service concern — but is
# documented as equally valid in docs/development/production-deployment.md. This module (MSK)
# is the lower-operational-overhead default for a team without existing Redpanda-on-Kubernetes
# expertise.

resource "aws_kms_key" "msk" {
  description             = "${var.name} MSK encryption"
  deletion_window_in_days = 30
  enable_key_rotation     = true
  tags                    = var.tags
}

resource "aws_security_group" "msk" {
  name_prefix = "${var.name}-msk-"
  vpc_id      = var.vpc_id

  ingress {
    description     = "Kafka TLS"
    from_port       = 9094
    to_port         = 9094
    protocol        = "tcp"
    security_groups = var.allowed_security_group_ids
  }
  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
  tags = merge(var.tags, { Name = "${var.name}-msk-sg" })
}

resource "aws_cloudwatch_log_group" "msk" {
  name              = "/aws/msk/${var.name}"
  retention_in_days = 30
  tags              = var.tags
}

resource "aws_msk_cluster" "this" {
  cluster_name           = var.name
  kafka_version           = var.kafka_version
  number_of_broker_nodes  = var.broker_count

  broker_node_group_info {
    instance_type   = var.broker_instance_type
    client_subnets  = var.private_subnet_ids
    security_groups = [aws_security_group.msk.id]

    storage_info {
      ebs_storage_info {
        volume_size = var.broker_ebs_volume_gb
      }
    }
  }

  encryption_info {
    encryption_at_rest_kms_key_arn = aws_kms_key.msk.arn
    encryption_in_transit {
      client_broker = "TLS"
      in_cluster    = true
    }
  }

  logging_info {
    broker_logs {
      cloudwatch_logs {
        enabled   = true
        log_group = aws_cloudwatch_log_group.msk.name
      }
    }
  }

  tags = var.tags
}
