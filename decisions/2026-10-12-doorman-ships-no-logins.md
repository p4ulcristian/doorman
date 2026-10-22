# doorman ships no logins

> Moved from Groundwork (2026-10-22), where it was "Groundwork ships no logins": the session was Groundwork's then and is doorman's now. What Groundwork ships stays in Groundwork's copy.

**Decision:** doorman has the session and "sign in as this person" (`sign-in!`); how a person proves who they are is the app's own login. An identity provider (a third-party subscription), a gate's header, an email link or a password: the app's code does the proving and ends by calling `sign-in!`. doorman depends on no identity provider.

**Why:** an identity provider is a third-party service and a subscription; a library that ships one ties every app to it. With the session in doorman, plugging any login in is one call at its end.

**Instead of:** shipping common logins and letting each app switch them on.
