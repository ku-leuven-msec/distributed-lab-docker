#!/bin/sh
# End-to-end check of the Kubernetes deployment in k8s/. Run from the repository root:
#
#   ./verify.sh              (cluster name defaults to ds-lab)
#   CLUSTER=other ./verify.sh
#
# It builds the image named in the manifests, loads it into the kind cluster,
# deploys everything from scratch, runs the verify Job, and then deletes server
# pod cellstore-1. The check passes only if every automated client serves
# partition 1 again within RECOVERY_S seconds, without being restarted.
# The script deletes and recreates all objects of k8s/ in the cluster.

set -u
CLUSTER=${CLUSTER:-ds-lab}
RECOVERY_S=${RECOVERY_S:-90}
FAILED=0

say()  { printf '\n== %s\n' "$*"; }
pass() { printf 'PASS  %s\n' "$*"; }
fail() { printf 'FAIL  %s\n' "$*"; FAILED=1; }
die()  { printf 'FAIL  %s\n' "$*"; printf '\nRESULT: FAILED\n'; exit 1; }

say "cluster and image"
[ "$(kubectl config current-context 2>/dev/null)" = "kind-$CLUSTER" ] \
  || die "kubectl context is not kind-$CLUSTER (kubectl config use-context kind-$CLUSTER)"
IMAGE=$(grep -h '^[[:space:]]*image:' k8s/*.yaml | awk '{print $2}' | sort -u)
[ "$(printf '%s\n' "$IMAGE" | wc -l | tr -d ' ')" = "1" ] \
  || die "all manifests must use the same image, found: $(printf "%s\n" "$IMAGE" | tr "\n" " ")"
printf 'image: %s\n' "$IMAGE"
docker build -q -t "$IMAGE" . >/dev/null || die "docker build failed"
kind load docker-image "$IMAGE" --name "$CLUSTER" >/dev/null || die "kind load failed"
pass "image $IMAGE built and loaded"

say "deploy from scratch"
kubectl delete -f k8s/ --ignore-not-found --wait=true >/dev/null 2>&1
kubectl apply -f k8s/config.yaml -f k8s/servers.yaml -f k8s/clients.yaml >/dev/null || die "kubectl apply failed"
kubectl rollout status statefulset/cellstore --timeout=180s >/dev/null || die "servers did not become ready"
kubectl rollout status deployment/cellstore-client --timeout=240s >/dev/null || die "clients did not become ready"
pass "3 servers and $(kubectl get deployment cellstore-client -o jsonpath='{.status.readyReplicas}') clients ready"

say "self-test (verify Job)"
kubectl apply -f k8s/verify.yaml >/dev/null || die "cannot create the verify Job"
kubectl wait --for=condition=complete job/verify --timeout=120s >/dev/null 2>&1
if kubectl logs job/verify 2>/dev/null | grep -q 'VERIFY OK'; then
  pass "verify Job: $(kubectl logs job/verify | grep 'VERIFY OK' | sed 's/.*VERIFY OK: //')"
else
  fail "verify Job did not print VERIFY OK:"
  kubectl logs job/verify 2>&1 | tail -n 5
fi

say "clients in steady state"
sleep 15
CLIENTS=$(kubectl get pods -l app=cellstore-client -o jsonpath='{range .items[*]}{.metadata.name}{"\n"}{end}')
restarts() { kubectl get pod "$1" -o jsonpath='{.status.containerStatuses[0].restartCount}'; }
BEFORE=""
for p in $CLIENTS; do BEFORE="$BEFORE $p=$(restarts "$p")"; done
N=$(printf '%s\n' "$CLIENTS" | grep -c .)
pass "$N client pods running"

say "chaos: delete server pod cellstore-1"
kubectl delete pod cellstore-1 --wait=true >/dev/null || die "cannot delete cellstore-1"
T0=$(date -u +%Y-%m-%dT%H:%M:%SZ)
printf 'deleted at %s, waiting up to %ss for all clients to serve partition 1 again\n' "$T0" "$RECOVERY_S"
START=$(date +%s)
while :; do
  OKCOUNT=0
  for p in $CLIENTS; do
    if kubectl logs "$p" --since-time="$T0" 2>/dev/null | grep -q 'OK cell=.* partition=1 server=cellstore-1'; then
      OKCOUNT=$((OKCOUNT + 1))
    fi
  done
  ELAPSED=$(( $(date +%s) - START ))
  [ "$OKCOUNT" -eq "$N" ] && break
  [ "$ELAPSED" -ge "$RECOVERY_S" ] && break
  sleep 5
done
if [ "$OKCOUNT" -eq "$N" ]; then
  pass "all $N clients served partition 1 again within ${ELAPSED}s"
else
  fail "only $OKCOUNT of $N clients served partition 1 again within ${RECOVERY_S}s"
fi

CHANGED=0
for entry in $BEFORE; do
  p=${entry%%=*}; old=${entry#*=}
  new=$(restarts "$p" 2>/dev/null || echo gone)
  [ "$new" = "$old" ] || { CHANGED=$((CHANGED + 1)); printf '      %s: restarts %s -> %s\n' "$p" "$old" "$new"; }
done
if [ "$CHANGED" -eq 0 ]; then
  pass "no client was restarted: they recovered by themselves"
else
  fail "$CHANGED client(s) were restarted or replaced instead of recovering"
fi

if [ "$FAILED" -eq 0 ]; then printf '\nRESULT: PASSED\n'; else printf '\nRESULT: FAILED\n'; exit 1; fi
