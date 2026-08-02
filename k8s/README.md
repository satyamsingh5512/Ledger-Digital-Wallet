# Kubernetes Manifests

Manifests are numbered to indicate apply order when using plain `kubectl apply -f k8s/`
(dependencies before dependents). Prefer `kubectl apply -k k8s/` (kustomize) which
handles this automatically via `kustomization.yaml`.

## Files

| File | Purpose |
|---|---|
| `00-namespace.yaml` | Dedicated `walletsys` namespace |
| `01-configmap.yaml` | Non-secret app configuration (env vars) |
| `02-secrets.yaml` | **Placeholder** secrets — replace before any real deployment, see file header |
| `10-app-deployment.yaml` | The application itself: 3 replicas, rolling updates, liveness/readiness probes, resource requests/limits |
| `11-app-service.yaml` | ClusterIP service in front of the app pods |
| `12-app-hpa.yaml` | Horizontal Pod Autoscaler (CPU + memory, 3–10 replicas) |
| `13-app-pdb.yaml` | PodDisruptionBudget (keeps ≥2 pods up during node drains) |
| `14-app-ingress.yaml` | Ingress (nginx-ingress + cert-manager assumed) |
| `20-dev-only-stateful-deps.yaml` | Postgres/Redis/Kafka StatefulSets — **local/dev clusters only**, see file header for why production should use managed services instead |

## Quick start (local cluster: kind / minikube)

```bash
kubectl apply -k k8s/
kubectl -n walletsys get pods -w
```

Update `10-app-deployment.yaml`'s image reference (`ghcr.io/OWNER/WalletSys:latest`) to
your actual pushed image before applying, and replace every placeholder value in
`02-secrets.yaml`.

## Production deployment notes

- Do not apply `20-dev-only-stateful-deps.yaml` in production. Point `01-configmap.yaml`
  (`DB_URL`, `REDIS_HOST`, `KAFKA_BOOTSTRAP_SERVERS`) at managed service endpoints
  (RDS/Aurora, ElastiCache, MSK) instead.
- Replace `02-secrets.yaml` with real secret management (External Secrets Operator,
  Sealed Secrets, or a manually-created Secret populated from your organization's
  secrets manager) — never commit real secret values to source control.
- Set `spec.tls` in `14-app-ingress.yaml` to your real domain, and confirm cert-manager
  (or your TLS termination approach of choice) is installed in-cluster.
- Consider a separate Kustomize overlay per environment (`overlays/staging`,
  `overlays/production`) once this needs to diverge beyond what's in this base — e.g.
  different replica counts, resource limits, or ingress hosts.
