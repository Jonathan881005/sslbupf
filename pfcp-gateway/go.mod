module github.com/briansp8210/sslbupf/pfcp-gateway

go 1.14

require (
	github.com/wmnsk/go-pfcp v0.0.11
	golang.org/x/sys v0.0.0-20210421221651-33663a62ff08 // indirect
	google.golang.org/grpc v1.40.0
	google.golang.org/protobuf v1.27.1
	gopkg.in/yaml.v2 v2.2.8
	k8s.io/api v0.19.0
	k8s.io/client-go v0.19.0
)

replace github.com/wmnsk/go-pfcp => github.com/briansp8210/go-pfcp v0.0.12-0.20210619063710-cc80ad12b36b
