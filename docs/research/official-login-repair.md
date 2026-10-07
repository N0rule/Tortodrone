# Official authentication after private-server login

On 2026-10-07, the official client reported "A server error caused authentication
to fail" after using the private server. Reinstalling the game did not resolve it.
The reinstalled `code/config.jar` matched the recorded official SHA-256 exactly,
and TCP connections to all four official game endpoints succeeded.

The private server's authentication response supplied `tortodrone-local` in
`ProjectXAuthResponseData.ident`. The installed client's logon observer
`com.threerings.projectx.client.dc.j(Client)` passes every non-null ident to
`com.threerings.util.J.gs(String)`, including responses to failed logins. This
persists the value outside the game installation. On this machine,
`~/.ypp_42` contained exactly that private-server marker.

The identifier writer deliberately backdates the file's modification time, so
its apparent age does not establish that it predates private-server testing.
Reinstalling game files leaves this home-directory state intact.

The repair backed up the file, removed only the confirmed private-server marker,
and relaunched the official Steam game so its own code could regenerate a valid
identifier. The controller workaround was not installed, and the repair did not
change the game archives, saved account password, controls, or private knight data.
The user confirmed that official-account login worked again after this repair.

The server now sends a null ident on every authentication result. Its integration
tests check that both accepted and rejected logins preserve client identity. An
ignored regression probe also deserializes the corrected responses with the
installed client and invokes its actual persistence hook against a temporary
identifier file, checking that its contents remain unchanged.

If repairing another client affected by an earlier build, close the game first,
back up its identifier, and reset it only when its contents are exactly the
known `tortodrone-local` marker. Leave other identifiers intact. Rebuild/restart
the private server before reconnecting, so an older running build cannot write
the marker again.
