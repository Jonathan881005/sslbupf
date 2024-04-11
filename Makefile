build:
	protoc --proto_path=apps/ha5gup/src/main/proto --go_out=pfcp-gateway/proto --go_opt=paths=source_relative --go-grpc_out=pfcp-gateway/proto --go-grpc_opt=paths=source_relative LoadBalancerAgent.proto
	cd apps/ha5gup && mvn clean install -Dmaven.test.skip=true
