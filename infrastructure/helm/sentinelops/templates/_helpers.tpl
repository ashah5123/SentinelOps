{{/*
Chart name and version, used for the standard "app.kubernetes.io/*" label set.
*/}}
{{- define "sentinelops.name" -}}
{{- .Chart.Name -}}
{{- end -}}

{{- define "sentinelops.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" -}}
{{- end -}}

{{- define "sentinelops.labels" -}}
app.kubernetes.io/name: {{ include "sentinelops.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ include "sentinelops.chart" . }}
{{- with .Values.commonLabels }}
{{ toYaml . }}
{{- end }}
{{- end -}}

{{/*
Per-component selector labels — deliberately a small, stable subset of sentinelops.labels so a
Deployment's selector never needs to change when unrelated labels (version, chart) change.
*/}}
{{- define "sentinelops.selectorLabels" -}}
app.kubernetes.io/name: {{ include "sentinelops.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/component: {{ .component }}
{{- end -}}

{{- define "sentinelops.serviceAccountName" -}}
{{- if .Values.serviceAccount.create -}}
{{ printf "%s-app" (include "sentinelops.name" .) }}
{{- else -}}
default
{{- end -}}
{{- end -}}
