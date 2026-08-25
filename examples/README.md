# Runnable examples

The examples demonstrate the two ODP integration roles with the public Java modules.

## Small Service

The small Service keeps a Collection and two Offerings in memory. It publishes a Service Document,
supports listing and searching Offerings, returns full Offering details, and exposes a free download
Action.

```sh
./scripts/run-small-service.sh
```

The Service listens on `http://127.0.0.1:4103` by default. Set `PORT` to use another port.

## Agent discovery

The Agent example composes a mock directory from reachable local Services, prints every inspected
Service Document, lists terse Offerings, and retrieves each full Offering. The mock directory is
example infrastructure; it does not call the canonical ODP directory.

Start the small Service in another terminal, then run:

```sh
./scripts/run-agent-example.sh
```

Without arguments, the mock directory checks ports 4101, 4102, and 4103. Pass one or more Service
origins to use a different set:

```sh
./scripts/run-agent-example.sh https://service.example
```
