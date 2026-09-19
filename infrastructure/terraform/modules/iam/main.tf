# Least-privilege IRSA role for the application's Kubernetes ServiceAccount (see
# infrastructure/helm/sentinelops/values-production.yaml's serviceAccount.annotations, which
# must be set to this role's ARN). Trusted only by the exact namespace/service-account pair
# named below — no other pod in the cluster, however it authenticates, can assume this role.
# Grants only: read the specific named secrets, and (if any bucket ARNs are passed) read/write
# objects in the specific named S3 buckets — never s3:*, never secretsmanager:* on "*".

data "aws_iam_policy_document" "assume_role" {
  statement {
    effect  = "Allow"
    actions = ["sts:AssumeRoleWithWebIdentity"]

    principals {
      type        = "Federated"
      identifiers = [var.oidc_provider_arn]
    }

    condition {
      test     = "StringEquals"
      variable = "${replace(var.oidc_provider_url, "https://", "")}:sub"
      values   = ["system:serviceaccount:${var.namespace}:${var.service_account_name}"]
    }
    condition {
      test     = "StringEquals"
      variable = "${replace(var.oidc_provider_url, "https://", "")}:aud"
      values   = ["sts.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "app" {
  name               = "${var.name}-app"
  assume_role_policy = data.aws_iam_policy_document.assume_role.json
  tags               = var.tags
}

data "aws_iam_policy_document" "app_permissions" {
  statement {
    sid       = "ReadApplicationSecrets"
    effect    = "Allow"
    actions   = ["secretsmanager:GetSecretValue"]
    resources = var.secret_arns
  }

  dynamic "statement" {
    for_each = length(var.s3_bucket_arns) > 0 ? [1] : []
    content {
      sid    = "ReadWriteObjectStorage"
      effect = "Allow"
      actions = [
        "s3:GetObject",
        "s3:PutObject",
        "s3:ListBucket",
      ]
      resources = concat(var.s3_bucket_arns, [for a in var.s3_bucket_arns : "${a}/*"])
    }
  }
}

resource "aws_iam_policy" "app" {
  name   = "${var.name}-app-permissions"
  policy = data.aws_iam_policy_document.app_permissions.json
}

resource "aws_iam_role_policy_attachment" "app" {
  role       = aws_iam_role.app.name
  policy_arn = aws_iam_policy.app.arn
}
