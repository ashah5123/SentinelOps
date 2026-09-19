# Container registry, one repository per service. IMMUTABLE tags mean a published version tag
# (e.g. "1.0.0") can never be silently overwritten — a real, previously-scanned image is always
# what a given tag refers to. Scan-on-push reuses the same Trivy-class scanning already run in
# CI (.github/workflows/ci.yml's vulnerability-scan job) as a second, independent check at the
# registry layer.

resource "aws_ecr_repository" "this" {
  for_each             = toset(var.repository_names)
  name                 = "sentinelops/${each.key}"
  image_tag_mutability = var.image_tag_mutability

  image_scanning_configuration {
    scan_on_push = true
  }
  encryption_configuration {
    encryption_type = "KMS"
  }
  tags = var.tags
}

resource "aws_ecr_lifecycle_policy" "this" {
  for_each   = aws_ecr_repository.this
  repository = each.value.name
  policy = jsonencode({
    rules = [{
      rulePriority = 1
      description  = "Expire untagged images after ${var.untagged_image_expiry_days} days"
      selection = {
        tagStatus   = "untagged"
        countType   = "sinceImagePushed"
        countUnit   = "days"
        countNumber = var.untagged_image_expiry_days
      }
      action = { type = "expire" }
    }]
  })
}
