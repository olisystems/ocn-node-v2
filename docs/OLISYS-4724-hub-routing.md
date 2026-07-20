# OLISYS-4724: Responsibility overview

| Responsibility | OCN Node Operator | `DE/BAN` without connected handler | `DE/BAN` with connected handler |
| --- | --- | --- | --- |
| **Registry identity** | `NodeOperator`: Ethereum address and node domain. | OCPI party `DE/BAN` with role `HUB`, linked to the Node Operator. | The same `DE/BAN` HUB party; no additional registry identity. |
| **Main role** | Operates the network node and routes OCN traffic. | Acts as a broadcast HUB destination. | Acts as a HUB destination with storage + broadcast. |
| **Authentication** | Uses the operator private key for node-to-node signatures. | Uses OCPI Credentials Tokens A/B/C with connected platforms. | The handler uses Tokens A/B/C and does not need the operator private key. |
| **Object routing** | Transports and validates requests. | Validates requests addressed to `DE/BAN`, then broadcasts them. | Forwards inbound pushes from other parties to the handler for storage, then broadcasts. |
| **Object storage** | Does not store OCPI business objects. | Does not store objects. | The connected handler stores and centralizes the objects. |
| **Broadcasting** | Provides the broadcast mechanism (`ModuleNotificationService`). | Broadcasts to eligible connected parties (module RECEIVER + whitelist). | Same broadcast — sending to `DE/BAN` over normal OCPI always triggers it. |
| **Object ownership** | Never becomes the object owner. | Preserves the original CPO/eMSP owner. | The handler stores objects under the original CPO/eMSP owner, not under `DE/BAN`. |
| **If the handler disconnects** | Keeps operating the node. | Broadcast remains active. | Automatically falls back to broadcast-only behavior. |

In short: the **Node Operator runs the transport infrastructure**, `DE/BAN` provides the
**OCPI HUB identity**, and the connected handler—when present—provides the **object storage**.
Broadcasting is always done by the node when a Locations/Tariffs/Tokens push is addressed to `DE/BAN`.

The special object routing currently covers `Locations`, `Tariffs`, and `Tokens` pushes.
