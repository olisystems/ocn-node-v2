# OLISYS-4724: Responsibility overview

| Responsibility | OCN Node Operator | `DE/BAN` without connected handler | `DE/BAN` with connected handler |
| --- | --- | --- | --- |
| **Registry identity** | `NodeOperator`: Ethereum address and node domain. | OCPI party `DE/BAN` with role `HUB`, linked to the Node Operator. | The same `DE/BAN` HUB party; no additional registry identity. |
| **Main role** | Operates the network node and routes OCN traffic. | Acts as a broadcast-only HUB destination. | Acts as a HUB destination backed by a storage service. |
| **Authentication** | Uses the operator private key for node-to-node signatures. | Uses OCPI Credentials Tokens A/B/C with connected platforms. | The handler uses Tokens A/B/C and does not need the operator private key. |
| **Object routing** | Transports and validates requests. | Validates requests addressed to `DE/BAN`, then broadcasts them. | Forwards requests addressed to `DE/BAN` only to the connected handler. |
| **Object storage** | Does not store OCPI business objects. | Does not store objects. | The connected handler stores and centralizes the objects. |
| **Broadcasting** | Provides the broadcast mechanism. | Broadcasts to eligible connected parties. | Does not broadcast while the handler is connected. |
| **Object ownership** | Never becomes the object owner. | Preserves the original CPO/eMSP owner. | The handler stores objects under the original CPO/eMSP owner, not under `DE/BAN`. |
| **If the handler disconnects** | Keeps operating the node. | Broadcast fallback remains active. | Automatically falls back to the no-handler behavior. |

In short: the **Node Operator runs the transport infrastructure**, `DE/BAN` provides the
**OCPI HUB identity**, and the connected handler—when present—provides the **object storage**.

The special object routing currently covers `Locations`, `Tariffs`, and `Tokens` pushes.
