# The web page script (`seat-map.js`): JavaScript in the browser, line by line

## What it is

`src/main/resources/static/seat-map.js` is the one JavaScript file of the repository. A browser downloads it from SeatWave (`GET /static/seat-map.js`) and runs it. It draws the seats of one section as a grid of buttons, refreshes them every two seconds, and holds a seat when the fan clicks one.

Terms you need.

- **A web page** is an **HTML** document. The browser reads it into a tree of boxes called the **DOM** (Document Object Model): the page, its buttons, its text. A script can change that tree, and the browser redraws.
- **JavaScript** is the language browsers run. A file of it is a **script**. This file is an **ES module**: it uses `export`, so a page loads it with `<script type="module" src="/static/seat-map.js">` or `import`.
- **`fetch`** is the browser's function for sending an HTTP request from a script. It returns a **promise**: a value that will arrive later. `await` pauses an **`async` function** until the promise is ready, without freezing the page.
- **A timer** (`setInterval`, `setTimeout`) runs a function later or repeatedly.
- **An event** is something that happened on the page (a click). A script **listens** for it with `addEventListener`.

## Why this repository uses it

The script is not there to be admired. It is a **static file with a cache lifetime**. The module's cache lab needs a file that the proxy may keep for 300 seconds and serve to every fan from its cache, and the seat page's script is the natural example. Its own first comment says so: "Served by SeatWave under /static/ with Cache-Control: public, max-age=300, so the reverse proxy may keep it for 300 seconds."

It also shows the client side of three module ideas:

- **Version 1 paging**: it follows `nextCursor` until the server stops sending one.
- **Idempotency**: it sends an `Idempotency-Key` with the hold and retries safely.
- **Back-off with jitter**: when a request fails it waits 1 then 2 seconds, with random jitter, as Module 4 taught.

There is no HTML page in the repository. The script is served; a page that loads it is yours to write. Nothing in the tests or the stack runs the script in a browser (the unit test only checks that the served body contains the text `loadSection`). The behavior described here was checked by reading the code and by running two of its functions under Node with a fake `fetch` (see "Try it yourself").

## Where it appears

| File | What it does |
|---|---|
| `src/main/resources/static/seat-map.js` | The script itself. |
| `backend/StaticFiles.java` | Reads it from the classpath at start and serves it at `/static/seat-map.js` as `text/javascript`. |
| `backend/SeatWaveRoutes.java` | Adds `Cache-Control: public, max-age=300` to it. |
| `backend/BackendServerTest.java` | `servesStaticFilesWithAFixedPublicLifetime` checks the headers and the text `loadSection`. |

## How it works underneath

**Loading.** The browser asks for the script. SeatWave answers with the bytes (3,703 of them), the media type `text/javascript` and the cache header. A browser or proxy that obeys the header keeps the file for 300 seconds. When the page runs `startSeatPage(container, customer)`, the script draws and starts its timer.

**Drawing.** `loadSection` fetches pages of 500 seats from `/v1/events/1/seats?section=A&limit=500&cursor=...` and returns the whole list. `drawSection` turns each seat into a `<button>` and replaces the old children of the container in one step.

**Holding.** A click on a seat button calls `holdSeat`, which sends `POST /v1/holds` with an `Idempotency-Key` that it makes once per click. The same key and body are sent on every retry of that click, so the server's key table (`HoldKeys`) makes retries safe.

**Paths are relative.** The script calls `/v1/events/...` with no host. The browser sends the request to whichever address served the page: SeatWave itself, or the proxy. That is why the same file works in front of either.

## Walkthrough of this repository's use

```javascript
// SeatWave seat page: draws one section's seats from the version 1 API and keeps them fresh.
// Served by SeatWave under /static/ with "Cache-Control: public, max-age=300", so the reverse
// proxy may keep it for 300 seconds and answer every fan from its cache.
"use strict";
```

Three comment lines say what the file is. `"use strict";` switches on **strict mode**: the engine reports some mistakes (such as assigning to a variable that was never declared) as errors instead of silently accepting them. Inside an ES module strict mode is already on; the line is a harmless reminder.

```javascript

const SEATWAVE_EVENT_ID = 1;
const SEATWAVE_PAGE_LIMIT = 500;
const SEATWAVE_REFRESH_MILLIS = 2000;
const SEATWAVE_STATE_CLASSES = Object.freeze({
  available: "seat seat--available",
  held: "seat seat--held",
  sold: "seat seat--sold",
});
```

Constants. `const` declares a name that is set once. `SEATWAVE_EVENT_ID` is 1 (the only event), `SEATWAVE_PAGE_LIMIT` is 500 (the largest page the server allows), and `SEATWAVE_REFRESH_MILLIS` is 2000 (two seconds). `Object.freeze({...})` makes an object that cannot be changed. It maps a seat's state (`available`, `held`, `sold`) to the CSS class names that the page's stylesheet would use to colour the button. (The server never writes `sold`; the script is ready for it.)

```javascript

// Reads every page of one section, following nextCursor until the server stops sending one.
async function loadSection(section) {
  const seats = [];
  let cursor = null;
  do {
    const query = new URLSearchParams({ section, limit: String(SEATWAVE_PAGE_LIMIT) });
    if (cursor !== null) {
      query.set("cursor", cursor);
    }
    const response = await fetch(`/v1/events/${SEATWAVE_EVENT_ID}/seats?${query}`, {
      headers: { Accept: "application/json" },
    });
    if (!response.ok) {
      throw new Error(`seat page answered ${response.status}`);
    }
    const page = await response.json();
    seats.push(...page.items);
    cursor = page.nextCursor ?? null;
  } while (cursor !== null);
  return seats;
}
```

`loadSection(section)` reads every page of one section.

- `async function` means the function returns a promise and may use `await` inside.
- `const seats = []` is the list being filled; `let cursor = null` is a variable that changes (`let`). `null` means "no cursor yet".
- `do { ... } while (cursor !== null)` runs the body at least once, then again while there is a cursor. `!==` is "not exactly equal".
- `new URLSearchParams({ section, limit: String(SEATWAVE_PAGE_LIMIT) })` builds a query string. `{ section, ... }` is shorthand for `{ section: section, ... }`. If there is a cursor, `query.set("cursor", cursor)` adds it. URLSearchParams encodes the characters safely.
- The template string between backticks, `` `/v1/events/${SEATWAVE_EVENT_ID}/seats?${query}` ``, puts values where `${...}` is. The second argument to `fetch` is an options object that sets the `Accept` header to `application/json`.
- `await fetch(...)` waits for the response headers. `response.ok` is true for status 200 to 299; otherwise the function throws an `Error` that includes the status.
- `await response.json()` reads and parses the body. `seats.push(...page.items)` appends every item (`...` spreads a list into separate arguments).
- `page.nextCursor ?? null` means: the value of `nextCursor`, or `null` if it is missing. The last page has no `nextCursor`, which ends the loop. (Missing fields are `undefined` in JavaScript; `??` turns both `undefined` and `null` into `null`.)

```javascript

// Draws the seats as a grid of buttons; a held or sold seat cannot be pressed.
function drawSection(container, seats) {
  const fragment = document.createDocumentFragment();
  for (const seat of seats) {
    const button = document.createElement("button");
    button.type = "button";
    button.textContent = seat.seat;
    button.className = SEATWAVE_STATE_CLASSES[seat.state] ?? "seat";
    button.disabled = seat.state !== "available";
    button.dataset.seat = seat.seat;
    fragment.appendChild(button);
  }
  container.replaceChildren(fragment);
}
```

`drawSection(container, seats)` redraws the grid.

- `document.createDocumentFragment()` makes an off-screen holder. Buttons are added to it first and put on the page in one step, so the browser draws once.
- `for (const seat of seats)` visits each seat. For each one: create a `<button>`; set `type` to `"button"` (so it never submits a form); set its text to the seat label; set its CSS class from the table, or the plain class `"seat"` if the state is unknown (`??`); set `disabled` to true unless the seat is available, so a held or sold seat cannot be clicked; and store the label in `button.dataset.seat`, which becomes the attribute `data-seat` that the click handler reads.
- `container.replaceChildren(fragment)` removes the old buttons and inserts the new ones.

```javascript

// Holds one seat with an Idempotency-Key, so a retry after a lost reply cannot hold twice.
async function holdSeat(seat, customer) {
  const key = `"${crypto.randomUUID()}"`;
  const body = JSON.stringify({ eventId: SEATWAVE_EVENT_ID, seat, customer });
  for (let attempt = 0; attempt < 3; attempt += 1) {
    try {
      const response = await fetch("/v1/holds", {
        method: "POST",
        headers: { "Content-Type": "application/json", "Idempotency-Key": key },
        body,
      });
      if (response.status === 201) {
        return await response.json();
      }
      if (response.status === 409) {
        return null;
      }
      if (response.status < 500) {
        throw new Error(`hold answered ${response.status}`);
      }
    } catch (networkError) {
      if (attempt === 2) {
        throw networkError;
      }
    }
    // Back off 1, then 2 seconds, with jitter, as Module 4 taught.
    const backoffMillis = 1000 * 2 ** attempt * (0.5 + Math.random());
    await new Promise((resolve) => setTimeout(resolve, backoffMillis));
  }
  throw new Error("hold did not succeed after three attempts");
}
```

`holdSeat(seat, customer)` tries to hold one seat and returns the hold, or `null`.

- ``const key = `"${crypto.randomUUID()}"` `` makes a random identifier and wraps it in double quotes. The server's rule for an Idempotency-Key is a quoted string of printable characters (an RFC 9651 string), so the quotes are part of the value. The key is made once, outside the loop, so every attempt of this click uses the same key.
- `JSON.stringify({ eventId, seat, customer })` writes the request body as JSON text. Note that `crypto.randomUUID` exists only in a **secure context**: HTTPS, or a page served from `localhost` or `127.0.0.1`. On plain HTTP to another host it is not available and the call fails.
- The `for` loop runs at most three attempts. Each does `fetch("/v1/holds", { method: "POST", headers: {...}, body })`. The shorthand `body` in the options means `body: body`.
- Status `201 Created`: return the parsed hold. Status `409`: return `null` (the seat is taken). Any other status below 500: `throw new Error(...)` (a client mistake; no point retrying). A status of 500 or more falls through to the wait and tries again.
- `try { ... } catch (networkError) { ... }` handles a failed `fetch` (no network). On the last attempt (`attempt === 2`) the error is thrown again; before that it is swallowed so the loop continues to the wait.
- The wait: `1000 * 2 ** attempt * (0.5 + Math.random())` milliseconds. `2 ** attempt` is two to the power of the attempt number (1, then 2). `Math.random()` gives a number from 0 up to 1, so the factor is from 0.5 to 1.5: the wait is between 0.5 and 1.5 seconds after the first attempt, and between 1 and 3 seconds after the second. This **jitter** stops many clients from retrying at the same instant. `await new Promise((resolve) => setTimeout(resolve, backoffMillis))` is the usual way to sleep in a script.
- If all three attempts end without a result, the last line throws "hold did not succeed after three attempts".

Two flaws in the retry logic, both confirmed by running `holdSeat` under Node with a fake `fetch` that always answers one status:

| Fake answer | What `holdSeat` did |
|---|---|
| 201 | returned the hold after 1 call |
| 409 | returned `null` after 1 call |
| 422 | threw `hold answered 422` after 3 calls and 2 waits |
| 503 | threw `hold did not succeed after three attempts` after 3 calls and 3 waits |

The 422 row shows the first flaw. The `throw new Error("hold answered ...")` for a status below 500 sits inside the `try` block, so the `catch` below it catches it, and on the first two attempts swallows it. A client mistake is therefore retried three times, with waits, before the error reaches the caller. The comment says a 4xx is not retried; the code does retry it. The second flaw is that every 409 means "seat taken" to this code. The server also answers 409 with the type `request-in-progress` when another request with the same key is still running; the script would report that as "seat taken" although the hold may be about to succeed. And the 503 row waits after its last attempt (3 waits for 3 calls) before throwing; the last wait is wasted.

```javascript

// Starts the page: draws section A, refreshes it every two seconds, and holds on a click.
export async function startSeatPage(container, customer) {
  let section = "A";
  const refresh = async () => drawSection(container, await loadSection(section));
  container.addEventListener("click", async (event) => {
    const seat = event.target?.dataset?.seat;
    if (!seat) {
      return;
    }
    const hold = await holdSeat(seat, customer);
    container.dispatchEvent(new CustomEvent("seatwave:hold", { detail: { seat, hold } }));
    await refresh();
  });
  await refresh();
  setInterval(() => refresh().catch(() => undefined), SEATWAVE_REFRESH_MILLIS);
  return {
    showSection: async (letter) => {
      section = letter;
      await refresh();
    },
  };
}
```

`startSeatPage(container, customer)` is the only export. A page calls it once.

- `export` makes the function available to a page that imports the module. `async` lets it use `await`.
- `let section = "A"` is the section shown now.
- `const refresh = async () => drawSection(container, await loadSection(section))` defines a function (an **arrow function**, `() => ...`) that loads the current section and draws it.
- `container.addEventListener("click", async (event) => {...})` listens for clicks on the container. One listener on the container handles every button (**event delegation**): `event.target` is the element that was clicked, `?.` safely reads `dataset.seat` even if some part is missing (it gives `undefined` instead of an error). If the click was not on a seat button, `!seat` is true and the handler returns.
- Otherwise it calls `holdSeat`, then fires a custom event `seatwave:hold` on the container with `new CustomEvent(name, { detail })`, carrying the seat and the hold (or `null`), so the page can show a message. Then it refreshes the grid. If `holdSeat` throws, the handler has no `catch`, so the error ends in the browser's console and the grid is not refreshed.
- `await refresh()` draws the first time. `setInterval(() => refresh().catch(() => undefined), SEATWAVE_REFRESH_MILLIS)` redraws every two seconds; `.catch(() => undefined)` ignores a failed refresh, so one dropped request does not stop the timer. A slow refresh does not stop the next tick, so refreshes can overlap.
- The function returns an object with one method, `showSection(letter)`, which changes `section` and refreshes.

## Try it yourself

Start SeatWave as in `java-backend-walkthrough-1.md`. Outputs are from real runs; timings differ on every run.

**1. Fetch the script the way a proxy would see it.**

```
curl -s -D - -o /dev/null http://127.0.0.1:20931/static/seat-map.js
```

```
HTTP/1.1 200 OK
Date: Wed, 11 Nov 2026 05:51:38 GMT
Content-type: text/javascript
Server-timing: app;dur=6.37
Content-length: 3703
Cache-control: public, max-age=300
```

`Cache-control: public, max-age=300` is the line that lets a shared cache keep the file for 300 seconds. `3703` is the size of the file.

**2. Fetch the poster.**

```
curl -s -o /dev/null -w '%{size_download} bytes, %{http_code}\n' http://127.0.0.1:20931/static/poster-arena.bin
```

It printed `300000 bytes, 200`.

**3. Ask for a page of seats like the script does.**

```
curl -s 'http://127.0.0.1:20931/v1/events/1/seats?section=A&limit=500' | head -c 160; echo
```

It printed the start of `{"items":[{"seat":"A-1","section":"A","state":"available"},...`.

**4. Check the syntax of the script with Node.** The file is an ES module, so give the copy the extension `.mjs`:

```
cp src/main/resources/static/seat-map.js seat-map.mjs
node --check seat-map.mjs && echo syntax ok
```

It printed `syntax ok`. I ran this with Node 24.14.1, in a scratch folder; Node is not required by the repository.

**5. Reproduce the retry table.** In a scratch folder, copy the script as `sm.mjs` with `async function startSeatPage` in place of `export async function startSeatPage`, add the line `export { holdSeat };` at the end, and run a short file that sets `globalThis.fetch` to return `{ status: 422, json: async () => ({}) }` and calls `await holdSeat("A-7", "fan-1")`. Count the calls. That is how the table above was produced. (Replace `setTimeout` with a function that calls the callback at once, or each run waits several seconds.)

## Mistakes people make

- **Throwing inside a `try` that catches everything.** The 4xx `throw` in `holdSeat` is caught by its own `catch`. Put a client-error `throw` outside the `try`, or rethrow it.
- **Treating every 409 alike.** The server uses 409 for two different problems (`seat-unavailable` and `request-in-progress`). Read the problem `type`, never the status alone.
- **Making a new key per retry.** A new key per attempt would make the server hold twice. The key is made once per click.
- **Retrying without back-off or jitter.** Retries from many clients at the same moment are a load spike. Back-off spreads them.
- **Relying on `crypto.randomUUID` over plain HTTP to another host.** It needs a secure context.
- **Forgetting that the file is cached.** After a change to the script, browsers and the proxy may serve the old copy for up to 300 seconds. A real site puts a version or a hash in the file name.
- **Overlapping refreshes.** `setInterval` starts a new refresh every two seconds whether or not the last one has finished.

## Check yourself

1. Why does the script call `/v1/events/1/seats?...` without a host? Answer: the browser sends it to the address that served the page, so the same file works against SeatWave directly or through the proxy.
2. How does the loop in `loadSection` know it has reached the last page? Answer: the last page has no `nextCursor`; `page.nextCursor ?? null` becomes `null` and the `while` test fails.
3. Why is the Idempotency-Key made before the retry loop? Answer: so every attempt of one click sends the same key; the server then returns the first answer instead of holding the seat again.
4. What does the wait between attempts look like, and what is jitter for? Answer: about 1 second (0.5 to 1.5) then about 2 seconds (1 to 3); the random factor spreads the retries of many clients over time.
5. What would a server answer if the script's cached copy is older than 300 seconds, and who decides? Answer: the cache (the browser or the proxy) must ask SeatWave again, because the `max-age=300` lifetime has ended; it then gets a fresh copy.

## Further reading

- ECMAScript Language Specification (modules, async functions, promises): https://tc39.es/ecma262/
- WHATWG Fetch Standard: https://fetch.spec.whatwg.org/
- WHATWG DOM Standard (events, `CustomEvent`, `DocumentFragment`): https://dom.spec.whatwg.org/
- WHATWG HTML Standard (`<script type="module">`, `data-*` attributes, timers): https://html.spec.whatwg.org/multipage/
- W3C Secure Contexts: https://www.w3.org/TR/secure-contexts/
- RFC 9111, HTTP Caching: https://www.rfc-editor.org/rfc/rfc9111
- RFC 9651, Structured Field Values for HTTP: https://www.rfc-editor.org/rfc/rfc9651
