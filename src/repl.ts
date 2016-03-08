import app = require("./app");
import {autoFocus} from "./utils";

enum CardState {
  NONE,
  GOOD,
  ERROR,
}

export interface Query {
  type: string,
  query: string,
  id: string,
}

interface ReplCard {
  id: string,
  state: CardState,
  focused: boolean,
  query: string,
  result: {
    fields: Array<string>,
    data: Array<Array<any>>,
  } | string,
}

app.renderRoots["repl"] = root;

let WebSocket = require('ws');
var server;
let uuid = require("uuid");

let ws: WebSocket = new WebSocket("ws://localhost:8080");

ws.onopen = function(e: Event) {
  console.log("Opening websocket connection.");
  console.log(e);
}

ws.onmessage = function(message: MessageEvent) {
  let parsed = JSON.parse(message.data);
  console.log("Received message!");
  // Update the result of the correct repl card
  let targetCard = replCards.filter((r) => r.id === parsed.id).shift();
  if (targetCard !== undefined) {
    if (parsed.type === "result") {
      targetCard.state = CardState.GOOD;
      targetCard.result = {
        fields: parsed.fields,
        data: parsed.values,
      }
    } else if (parsed.type === "error") {
      targetCard.state = CardState.ERROR;
      targetCard.result = parsed.cause;
    }
  }
  app.dispatch("rerender", {}).commit();
}

ws.onerror = function(e: Event) {
  console.log("Websocket error!");
}

ws.onclose = function(c: CloseEvent) {
  console.log("Closing websocket connection.");
}

function sendQuery(ws: WebSocket, query: Query) {
  console.log("Sending query:");
  console.log(query);
  if (ws.readyState === ws.OPEN) {
    ws.send(JSON.stringify(query));  
  }
}

function newReplCard(): ReplCard {
  let replCard: ReplCard = {
    id: uuid(),
    state: CardState.NONE,
    focused: false,
    query: undefined,
    result: undefined,
  }
  return replCard;
}

function queryInputKeydown(event, elem) {
  let textArea = event.srcElement;
  let thisReplCard = textArea.parentElement;
  let replIDs = replCards.map((r) => r.id);
  let thisReplCardIx = replIDs.indexOf(thisReplCard._id);
  // Submit the query with ctrl + enter
  if (event.keyCode === 13 && event.ctrlKey === true) {
    let queryString = textArea.value;
    let query: Query = {
      id: thisReplCard._id,
      type: "query",
      query: queryString,
    }
    sendQuery(ws, query);
    // Create a new card if we submitted the last one in replCards
    if (thisReplCardIx === replCards.length - 1) {
      let nReplCard = newReplCard();
      replCards.forEach((r) => r.focused = false);
      nReplCard.focused = true;
      replCards.push(nReplCard);
    }
    app.dispatch("rerender", {}).commit();
  // Catch tab
  } else if (event.keyCode === 9) {
    let start = textArea.selectionStart;
    let end = textArea.selectionEnd;
    let value = textArea.value;
    value = value.substring(0, start) + "  " + value.substring(end);
    textArea.value = value;
    textArea.selectionStart = textArea.selectionEnd = start + 2;
    event.preventDefault();
  // Catch ctrl + arrow up or page up
  } else if (event.keyCode === 38 && event.ctrlKey === true || event.keyCode === 33) {
    // Set the focus to the previous repl card
    let previousIx = thisReplCardIx - 1 >= 0 ? thisReplCardIx - 1 : 0;
    replCards.forEach((r) => r.focused = false);
    replCards[previousIx].focused = true;
    app.dispatch("rerender", {}).commit();
  // Catch ctrl + arrow down or page down
  } else if (event.keyCode === 40 && event.ctrlKey === true || event.keyCode === 34) {
    // Set the focus to the next repl card
    let nextIx = thisReplCardIx + 1 <= replIDs.length - 1 ? thisReplCardIx + 1 : replIDs.length - 1;
    replCards.forEach((r) => r.focused = false);
    replCards[nextIx].focused = true;
    app.dispatch("rerender", {}).commit();
  }
}

function newReplCardElement(replCard: ReplCard) {
  let queryInput = {t: "textarea", c: "query-input", placeholder: "query", keydown: queryInputKeydown, postRender: replCard.focused === true ? autoFocus : undefined};
  // Set the css according to the card state
  let resultcss;
  if (replCard.state === CardState.NONE) {
    resultcss = "query-result";
  } else if (replCard.state === CardState.GOOD) {
    resultcss = "query-result-good";
  } else if (replCard.state === CardState.ERROR) {
    resultcss = "query-result-bad";
  }
  let queryResult = replCard.result === undefined ? {} : {c: resultcss, text: JSON.stringify(replCard.result)};
  let replCardElement = {
    id: replCard.id,
    c: "repl-card",
    children: [queryInput, queryResult],
  };
  return replCardElement;
}

// Create an initial repl card
let replCards: Array<ReplCard> = [newReplCard()];
function root() {
  let replroot = {
    id: "root",
    c: "repl-root",
    children: replCards.map(newReplCardElement),
  };
  return replroot;
}