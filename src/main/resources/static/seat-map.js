// SeatWave seat page: draws one section's seats from the version 1 API and keeps them fresh.
// Served by SeatWave under /static/ with "Cache-Control: public, max-age=300", so the reverse
// proxy may keep it for 300 seconds and answer every fan from its cache.
"use strict";

const SEATWAVE_EVENT_ID = 1;
const SEATWAVE_PAGE_LIMIT = 500;
const SEATWAVE_REFRESH_MILLIS = 2000;
const SEATWAVE_STATE_CLASSES = Object.freeze({
  available: "seat seat--available",
  held: "seat seat--held",
  sold: "seat seat--sold",
});

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

// Holds one seat with an Idempotency-Key, so a retry after a lost reply cannot hold twice. Only a
// lost connection, a 5xx answer or "request-in-progress" (the same key is still running) is tried
// again; any other answer is final.
const SEATWAVE_HOLD_ATTEMPTS = 3;
const SEATWAVE_IN_PROGRESS = "https://seatwave.example/problems/request-in-progress";

async function holdSeat(seat, customer) {
  const key = `"${crypto.randomUUID()}"`;
  const body = JSON.stringify({ eventId: SEATWAVE_EVENT_ID, seat, customer });
  for (let attempt = 1; attempt <= SEATWAVE_HOLD_ATTEMPTS; attempt += 1) {
    let response = null;
    try {
      response = await fetch("/v1/holds", {
        method: "POST",
        headers: { "Content-Type": "application/json", "Idempotency-Key": key },
        body,
      });
    } catch (networkError) {
      if (attempt === SEATWAVE_HOLD_ATTEMPTS) {
        throw networkError;
      }
    }
    if (response !== null) {
      if (response.status === 201) {
        return await response.json();
      }
      if (response.status === 409) {
        const problem = await response.json();
        if (problem.type !== SEATWAVE_IN_PROGRESS) {
          // The seat is held or sold.
          return null;
        }
      } else if (response.status < 500) {
        // A 4xx other than 409 says the request itself is wrong; sending it again cannot help.
        throw new Error(`hold answered ${response.status}`);
      }
    }
    if (attempt < SEATWAVE_HOLD_ATTEMPTS) {
      // Back off 1, then 2 seconds, with jitter, as Module 4 taught.
      const backoffMillis = 1000 * 2 ** (attempt - 1) * (0.5 + Math.random());
      await new Promise((resolve) => setTimeout(resolve, backoffMillis));
    }
  }
  throw new Error(`hold did not succeed after ${SEATWAVE_HOLD_ATTEMPTS} attempts`);
}

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
