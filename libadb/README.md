libadb-android subset
=====================
This directory contains the small part of libadb-android used by
scrcpy-android. It is based on upstream 3.1.1 at commit
`c849886ebc6d48e7b46d967e78a6bb65c90c3b74` and carries local protocol,
timeout, TLS enforcement, and stream-flow-control fixes.


Scope
-----
Only direct TCP connection, TLS pairing, ADB authentication, and byte streams
are retained. The unused connection manager, mDNS discovery, service catalog,
legacy PRNG workaround, sample code, and publishing configuration are omitted.
The application owns endpoint validation and the persistent ADB identity.


Local changes
-------------
- Require TLS and reject legacy authentication.
- Validate packet headers, commands, stream IDs, sizes, and negotiated limits.
- Bound TCP connect, protocol waits, stream opens, and connection close.
- Acknowledge one fully consumed WRTE at a time and drain data before CLSE.
- Serialize WRTE with close so no payload follows CLSE.
- Keep the caller-owned keypair alive across routine disconnects.
- Call the pinned public Conscrypt APIs directly.
- Remove Android services and compatibility paths outside the stated scope.


Updating
--------
Vendor updates are manual because the local security changes overlap the
upstream transport. Compare a new upstream tag against this directory, port
only the required files, and reapply the inventory above. The upstream commit
is the comparison point; source comments are not a complete patch ledger. Run
`./scripts/check` and `./test e2e`, then update the tag and commit above.


License
-------
See `COPYING` and `LICENSES/`. Individual source files retain their SPDX
identifiers.
