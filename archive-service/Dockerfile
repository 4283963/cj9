FROM golang:1.21-alpine AS builder

WORKDIR /app

COPY go.mod go.sum ./
RUN go mod download

COPY . .

RUN CGO_ENABLED=0 GOOS=linux go build -a -installsuffix cgo -o archive-service .

FROM alpine:latest

RUN apk --no-cache add ca-certificates tzdata

WORKDIR /app

RUN addgroup -g 1001 -S appgroup && \
    adduser -S appuser -u 1001 -G appgroup

USER appuser

COPY --from=builder /app/archive-service .

EXPOSE 8081

CMD ["./archive-service"]
