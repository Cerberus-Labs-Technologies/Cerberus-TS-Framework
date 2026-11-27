#!/usr/bin/env python3
"""Check the pinned, official TS6 help archive without executing its server binary."""
import argparse
import hashlib
import io
import re
import tarfile
import urllib.request
from pathlib import Path

URL = "https://github.com/teamspeak/teamspeak6-server/releases/download/v6.0.0-beta14/teamspeak6-server-linux-arm64.tar.xz"
SHA256 = "06c25ad0d875a65b2ffb2361a84916b54f3ca7b2de61f959f7e15fa53ddec1fa"
# Parameter names are protocol facts, independently recorded from the shipped help.
COMMANDS = {
    "login": {"client_login_name", "client_login_password"},
    "use": {"sid", "port", "client_nickname"},
    "clientupdate": {"client_nickname"},
    "servernotifyregister": {"event", "id"},
    "clientinfo": {"clid"},
    "sendtextmessage": {"targetmode", "target", "msg"},
    "clientpoke": {"clid", "msg"},
    "clientmove": {"clid", "cid", "cpw"},
    "clientkick": {"clid", "reasonid", "reasonmsg"},
    "clientdbinfo": {"cldbid"},
    "clientdbfind": {"pattern"},
    "permidgetbyname": {"permsid"},
    "permoverview": {"cid", "cldbid", "permid", "permsid"},
    "ftinitdownload": {"clientftfid", "name", "cid", "cpw", "seekpos", "proto"},
    "ftinitupload": {"clientftfid", "name", "cid", "cpw", "size", "overwrite", "resume", "proto"},
    "serveredit": {"virtualserver_name"},
    "channelcreate": {"channel_name"},
    "channelinfo": {"cid"},
    "channeledit": {"cid", "channel_description"},
    "channelmove": {"cid", "cpid", "order"},
    "channeldelete": {"cid", "force"},
    "channeladdperm": {"cid", "permsid", "permvalue"},
    "channeldelperm": {"cid", "permsid"},
    "channelpermlist": {"cid"},
    "servergroupadd": {"name", "type"},
    "servergroupcopy": {"ssgid", "tsgid", "name", "type"},
    "servergrouprename": {"sgid", "name"},
    "servergroupdel": {"sgid", "force"},
    "servergroupaddperm": {"sgid", "permsid", "permvalue", "permnegated", "permskip"},
    "servergroupdelperm": {"sgid", "permsid"},
    "servergrouppermlist": {"sgid"},
    "servergroupclientlist": {"sgid"},
    "channelgroupadd": {"name", "type"},
    "channelgroupcopy": {"scgid", "tcgid", "name", "type"},
    "channelgrouprename": {"cgid", "name"},
    "channelgroupdel": {"cgid", "force"},
    "channelgroupaddperm": {"cgid", "permsid", "permvalue"},
    "channelgroupdelperm": {"cgid", "permsid"},
    "channelgrouppermlist": {"cgid"},
    "channelgroupclientlist": {"cgid"},
}


def check(content):
    if hashlib.sha256(content).hexdigest() != SHA256:
        raise ValueError("Official archive checksum mismatch")
    with tarfile.open(fileobj=io.BytesIO(content), mode="r:xz") as archive:
        def read(name):
            member = archive.getmember(name)
            if not member.isfile() or member.size > 2_000_000:
                raise ValueError("Unexpected documentation member")
            # Read selected members in memory; never extract archive paths to disk.
            return archive.extractfile(member).read().decode("utf-8")

        for command, expected in COMMANDS.items():
            text = read(f"serverquerydocs/{command}.txt")
            # Several commands use open-ended properties; their named properties appear in examples.
            actual = set(re.findall(r"\b([a-z_]+)=", text))
            missing = expected - actual
            if missing:
                raise ValueError(f"{command}: expected parameters missing: {sorted(missing)}")
        web = read("doc/server/webquery.md")
        for marker in ["x-api-key", "/byport/", "POST", "JSON array", "servernotifyregister", "ft*"]:
            if marker not in web:
                raise ValueError(f"WebQuery documentation changed: {marker}")
    print(f"TS6 beta14 archive verified; {len(COMMANDS)} command parameter sets and WebQuery features checked.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--archive", type=Path, help="Use an already downloaded official .tar.xz archive")
    args = parser.parse_args()
    if args.archive:
        content = args.archive.read_bytes()
    else:
        with urllib.request.urlopen(URL, timeout=60) as response:
            content = response.read(30_000_001)
        if len(content) > 30_000_000:
            raise ValueError("Archive unexpectedly large")
    check(content)


if __name__ == "__main__":
    main()
