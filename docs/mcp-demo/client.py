import asyncio
import json
import sys

from mcp import ClientSession
from mcp.client.sse import sse_client

URL = "http://127.0.0.1:8080/sse"


async def main() -> int:
    async with sse_client(URL) as (read, write):
        async with ClientSession(read, write) as session:
            init = await session.initialize()
            print("initialize ->", init.server_info.name, init.server_info.version, init.protocol_version)

            tools = await session.list_tools()
            names = sorted(t.name for t in tools.tools)
            print("tools/list ->", len(names), "tools")
            for name in names:
                print("  ", name)
            if len(names) != 7:
                print("FAIL: expected 7 tools")
                return 1

            result = await session.call_tool("getPod", {"shipmentId": "SHP-1001"})
            text = "".join(getattr(c, "text", "") for c in result.content)
            print("tools/call getPod(SHP-1001) ->")
            print(json.dumps(json.loads(text), indent=2))
            payload = json.loads(text)
            ok = payload.get("found") is True and [i.lower() for i in payload.get("evidenceIds", [])] == ["pod:shp-1001"]
            print("PASS" if ok else "FAIL")
            return 0 if ok else 1


sys.exit(asyncio.run(main()))
