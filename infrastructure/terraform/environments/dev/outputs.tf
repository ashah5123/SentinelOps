output "cluster_name" {
  value = module.eks.cluster_name
}

output "cluster_endpoint" {
  value = module.eks.cluster_endpoint
}

output "postgres_endpoint" {
  value = module.postgres.endpoint
}

output "redis_endpoint" {
  value = module.redis.primary_endpoint
}

output "kafka_bootstrap_brokers" {
  value = module.kafka.bootstrap_brokers_tls
}

output "ecr_repository_urls" {
  value = module.ecr.repository_urls
}

output "app_iam_role_arn" {
  value = module.iam.role_arn
}
