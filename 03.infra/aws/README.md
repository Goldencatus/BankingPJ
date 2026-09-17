# AWS 배포 준비

목표 구성은 Frontend `S3 + CloudFront`, Backend `EC2 + Docker`, Database `RDS MySQL`입니다. GitHub Actions는 이 단계에 포함하지 않습니다.

## Backend Docker

프로젝트 루트에서 image를 생성합니다.

```powershell
docker build -t bankingpj-backend:latest .\02.backend
```

EC2에서는 실제 값을 환경변수 또는 EC2용 secret 주입 방식으로 전달합니다. 명령 이력에 secret을 직접 남기지 않으려면 EC2에서 별도 `--env-file`을 만들고 Git에 추가하지 않습니다.

```powershell
docker run -d `
  --name bankingpj-backend `
  --restart unless-stopped `
  -p 8080:8080 `
  --env-file <사용자-지정-env-file-경로> `
  bankingpj-backend:latest
```

Backend에 필요한 환경변수:

```text
DB_HOST=<RDS-endpoint>
DB_PORT=<RDS-port>
DB_NAME=<RDS-database-name>
DB_USERNAME=<RDS-username>
DB_PASSWORD=<RDS-password>
DB_SSL_MODE=<RDS-SSL-mode>
JWT_ACCESS_SECRET=<Base64-encoded-random-secret>
JWT_ACCESS_TTL_SECONDS=<access-token-TTL-seconds>
JWT_REFRESH_TTL_SECONDS=<refresh-token-TTL-seconds>
AUTH_COOKIE_SECURE=true
CORS_ALLOWED_ORIGINS=<CloudFront-or-custom-Frontend-origin>
```

`CORS_ALLOWED_ORIGINS`는 쉼표로 구분한 명시적 Origin만 허용하며 `*`를 사용하지 않습니다. 예시는 실제 도메인 대신 `<사용자 지정값>`으로 관리합니다.

RDS 보안 그룹은 Backend EC2의 보안 그룹에서 오는 MySQL 포트만 허용합니다. 애플리케이션은 시작할 때 기존 Flyway migration을 적용한 뒤 Hibernate schema를 `validate`합니다. 배포 확인은 다음 endpoint를 사용합니다.

```text
GET http://<EC2-or-Backend-domain>:8080/actuator/health
```

STEP 16 Seeder는 별도 Gradle task를 명시적으로 실행할 때만 동작합니다. 운영 AWS/RDS 배포 과정에서는 `seedData`와 `resetSeedData`를 실행하지 않습니다.

## Frontend S3 + CloudFront

Vite 환경변수는 build 시점에 주입되며 브라우저에 공개되므로 secret을 넣지 않습니다.

```powershell
cd .\01.frontend
$env:VITE_API_BASE_URL="https://<사용자-지정-Backend-domain>"
npm ci
npm run build
```

생성된 `01.frontend/dist` 내용을 private S3 bucket에 업로드하고 CloudFront Origin Access Control을 통해 배포합니다. React Router 새로고침을 위해 CloudFront의 403/404 응답을 `/index.html`의 HTTP 200 응답으로 매핑합니다.

Refresh Token Cookie는 `Secure`와 `SameSite=Strict`를 사용합니다. 운영에서는 Frontend와 API에 같은 상위 도메인의 custom domain을 사용하거나, CloudFront에서 API 경로를 Backend origin으로 전달해 브라우저 기준 같은 사이트가 되도록 구성합니다. Frontend와 API가 서로 다른 사이트면 Strict Cookie가 전송되지 않습니다.

CloudFront 배포가 바뀐 뒤 필요할 때만 캐시 무효화를 실행합니다.

```powershell
aws cloudfront create-invalidation `
  --distribution-id <사용자-지정-distribution-id> `
  --paths "/*"
```
