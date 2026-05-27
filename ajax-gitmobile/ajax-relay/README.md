# ajax-relay

Spring Boot 3.x service (Java 21) that fronts a single EC2 dev box. Three jobs:

1. **EC2 lifecycle** — start/stop the dev instance via AWS SDK v2 based on session activity.
2. **SSH bridge** — proxy an interactive SSH shell to the dev instance over a WebSocket.
3. **Auto-stop** — shut the instance down after a configurable idle window.

Single-user auth: bearer token from env var. State persists in SQLite.

## Endpoints

All endpoints (except `/actuator/health`) require `Authorization: Bearer $RELAY_BEARER_TOKEN`.

| Method | Path                    | Purpose |
|--------|-------------------------|---------|
| POST   | `/api/session/start`    | Boot dev EC2 if stopped; idempotent if already running |
| POST   | `/api/session/stop`     | Stop dev EC2 |
| POST   | `/api/session/heartbeat`| Update `last_active` to defer auto-stop |
| GET    | `/api/session/status`   | Current AWS + tracked state, `lastActive` |
| WS     | `/api/shell`            | Binary WebSocket; bytes are piped to/from the SSH shell |

## Local build

```bash
mvn clean package -DskipTests
```

Produces `target/relay-0.1.0.jar`.

## Run locally

Copy `.env.example` → `.env` and fill it in (a real AWS instance ID, your private key path, a long random token), then:

```bash
set -a && source .env && set +a
java -jar target/relay-0.1.0.jar
```

## Deployment (relay EC2)

Assumes a small relay instance with an elastic IP, in the same VPC/SG as the dev box.

1. **Build locally**
   ```bash
   mvn clean package -DskipTests
   ```
2. **Copy artifacts to the relay**
   ```bash
   RELAY=ec2-user@<relay-eip>
   scp -i ~/.ssh/ajax-dev-relay.pem target/relay-0.1.0.jar "$RELAY":~/
   scp -i ~/.ssh/ajax-dev-relay.pem deploy/ajax-relay.service "$RELAY":~/
   ```
3. **SSH in, install Java 21**
   ```bash
   ssh -i ~/.ssh/ajax-dev-relay.pem "$RELAY"
   sudo dnf install -y java-21-amazon-corretto
   ```
4. **Lay out directories & install jar**
   ```bash
   sudo mkdir -p /opt/ajax-relay /var/lib/ajax-relay /var/log/ajax-relay /etc/ajax-relay
   sudo mv ~/relay-0.1.0.jar /opt/ajax-relay/
   sudo chown -R ec2-user:ec2-user /opt/ajax-relay /var/lib/ajax-relay /var/log/ajax-relay
   ```
5. **Install the SSH key the relay uses to reach the dev box**
   ```bash
   mkdir -p ~/.ssh
   # paste the private key
   chmod 600 ~/.ssh/relay-to-dev
   ```
6. **Write `/etc/ajax-relay/relay.env`** (root-owned, mode 0640):
   ```ini
   RELAY_BEARER_TOKEN=<long-random-string>
   AWS_REGION=us-east-2
   DEV_INSTANCE_ID=i-xxxxxxxxxxxxxxxxx
   DEV_HOST=172.31.9.200
   DEV_USER=ec2-user
   DEV_SSH_KEY_PATH=/home/ec2-user/.ssh/relay-to-dev
   IDLE_THRESHOLD_MINUTES=15
   IDLE_CHECK_INTERVAL_SECONDS=60
   SSH_READY_TIMEOUT_SECONDS=120
   SPRING_PROFILES_ACTIVE=prod
   ```
   Then `sudo chmod 640 /etc/ajax-relay/relay.env`.
7. **Install systemd unit & start**
   ```bash
   sudo mv ~/ajax-relay.service /etc/systemd/system/
   sudo systemctl daemon-reload
   sudo systemctl enable --now ajax-relay
   sudo systemctl status ajax-relay
   ```
8. **TLS** — either run Nginx out front with Let's Encrypt, or enable Spring Boot's built-in TLS via `server.ssl.*` in `application-prod.yml`. WebSocket clients hitting `/api/shell` need WSS in prod.

## IAM

The relay instance needs an instance role with **least-privilege** EC2 permissions scoped to the dev instance ARN:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": ["ec2:StartInstances", "ec2:StopInstances"],
      "Resource": "arn:aws:ec2:us-east-2:<account-id>:instance/i-xxxxxxxxxxxxxxxxx"
    },
    {
      "Effect": "Allow",
      "Action": "ec2:DescribeInstances",
      "Resource": "*"
    }
  ]
}
```

## Idle behavior

`IdleMonitorService` runs every `session.idle-check-interval-seconds`. If the tracked status is `RUNNING` and `now - last_active >= idle-threshold-minutes`, it stops the instance. Any REST call (`/start`, `/heartbeat`, `/status` is read-only and does not touch) or WebSocket data resets `last_active`.

## Testing

```bash
mvn test
```

Two suites:
- `Ec2ServiceTest` — mocks `Ec2Client`, verifies state mapping and start/stop calls.
- `SessionControllerTest` — MockMvc happy paths for `/start`, `/stop`, `/heartbeat`, `/status`.
