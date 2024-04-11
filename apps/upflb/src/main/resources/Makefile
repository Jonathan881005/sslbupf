all: upflb constants

upflb: upflb.p4
	@./compile.sh "upflb" "-g"

constants:
	docker run --rm -v $(ONOS_ROOT):/onos -w /onos/tools/dev/bin \
		--entrypoint ./onos-gen-p4-constants opennetworking/p4mn:stable \
		-o /onos/pipelines/upflb/src/main/java/org/onosproject/pipelines/upflb/UpflbConstants.java \
		upflb /onos/pipelines/upflb/src/main/resources/p4c-out/upflb_p4info.txt

clean:
	rm -rf p4c-out/*
