COMPILE_DEPS = CORE_DEPS + [
    "@minimal_json//jar",
    "//protocols/p4runtime/model:onos-protocols-p4runtime-model",
    "//protocols/p4runtime/api:onos-protocols-p4runtime-api",
]

BUNDLES = [
    "//pipelines/upflb:onos-pipelines-upflb",
]

osgi_jar(
    deps = COMPILE_DEPS,
)

onos_app(
    app_name = "org.onosproject.pipelines.upflb",
    category = "Pipeline",
    description = "A layer7 UPF load balancer.",
    included_bundles = BUNDLES,
    required_apps = [
        "org.onosproject.drivers.p4runtime",
    ],
    title = "UPF load balancer",
    url = "http://onosproject.org",
)
