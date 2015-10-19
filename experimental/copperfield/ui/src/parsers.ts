module Parsers {
  //---------------------------------------------------------------------------
  // Types
  //---------------------------------------------------------------------------
  interface Token { type: string, tokenIx?: number, lineIx?: number, indent?: number }
  interface LineAST extends Token { chunks: Token[] }

  interface CommentAST extends Token { text: string }
  interface TextAST extends Token { text: string }
  interface KeywordAST extends TextAST {}
  interface FieldAST extends Token { grouped?: boolean, alias?: string, value?: string }
  interface SourceAST extends LineAST { negated?: boolean }
  interface OrdinalAST extends LineAST { alias: string, directions: string[] }
  interface CalculationAST extends LineAST { partIx?: number, text?: string }
  export interface QueryAST extends LineAST {}

  interface ElementAST extends Token { tag?: string, classes?: string, name?: string }
  interface AttributeAST extends Token { property: string, value: FieldAST, static: boolean }
  interface BindingAST extends Token { text: string }
  interface EventAST extends Token { event: string, key?: FieldAST }
  export interface UiAST extends LineAST {}

  interface FieldIR { field: string, grouped?: boolean, alias?: string, value?: string, ordinal?: boolean }
  interface SourceIR {
    source: string
    sourceView: string
    fields: FieldIR[]
    negated?: boolean
    chunked?: boolean
    sort?: {ix:number, field:string, direction:string}[]
    ordinal?: string|boolean //alias
    lineIx?: number
  }
  interface VariableIR {
    selected: string,
    alias?:string,
    value?: any,
    ordinals?: string[],
    bindings: {source: string, field: string}[]
  }
  export interface QueryIR {
    id: string
    sources: SourceIR[]
    aliases: {[alias:string]: string}
    variables: {[id:string]: VariableIR}
    actions: any[]
  }
  interface ElementIR {
    element: string
    tag: string
    ix: number
    name?: string
    parent?: string
    attributes: Api.Dict
    boundAttributes: Api.Dict
    events: string[]
    boundEvents: Api.Dict
    boundView?: string
    bindings?: Api.Dict
  }
  export interface UiIR {
    elements: ElementIR[]
    root: ElementIR
    boundQueries: {[id: string]: Query}
  }

  function tokenIsLine(token:Token): token is LineAST { return !!token["chunks"]; }
  function tokenIsText(token:Token): token is TextAST { return token.type === "text"; }
  function tokenIsComment(token:Token): token is CommentAST { return token.type === "comment"; }

  function tokenIsKeyword(token:Token): token is TextAST { return token.type === "keyword"; }
  function tokenIsField(token:Token): token is FieldAST { return token.type === "field"; }
  function tokenIsSource(token:Token): token is SourceAST {return token.type === "source"; }
  function tokenIsOrdinal(token:Token): token is OrdinalAST { return token.type === "ordinal"; }
  export function tokenIsCalculation(token:Token): token is CalculationAST { return token.type === "calculation"; }
  function tokenIsAction(token:Token): token is LineAST { return token.type === "action"; }

  function tokenIsAttribute(token:Token): token is AttributeAST { return token.type === "attribute"; }
  function tokenIsBinding(token:Token): token is BindingAST { return token.type === "binding"; }
  function tokenIsEvent(token:Token): token is EventAST { return token.type === "event"; }
  function tokenIsElement(token:Token): token is ElementAST { return token.type === "element"; }

  //---------------------------------------------------------------------------
  // Utilities
  //---------------------------------------------------------------------------
  const PUNCTUATION = [".", ",", ";", ":"];

  interface ParseError {
    (msg:string, token?:any): Error
    reset: () => void
    tokenToChar: (token:any, line:string) => number
    lines: string[]
    lineIx: number
  }
  var ParseError:ParseError = <any>function(msg, token?:any) {
    let {lines = [], lineIx} = ParseError;
    let line = lines[lineIx] || "";
    let charIx = 0;
    let length = line.length;
    if(token) {
      lineIx = (token.lineIx !== undefined) ? token.lineIx : lineIx;
      line = lines[lineIx] || "";
      charIx = ParseError.tokenToChar(token, line);
      let text = tokenToString(token) || "";
      let ix = text.indexOf("\n");
      if(ix !== -1) length = ix - charIx;
      else length = (text.length || line.length) - charIx;
    }
    msg += `\nOn line ${lineIx}:${charIx}`;
    if(line !== undefined) {
      msg += `\n${line}\n${underline(charIx, length)}`;
    }
    let err = <any>new Error(msg);
    err.name = "Parse Error";
    err.line = line;
    err.lineIx = lineIx;
    err.charIx = charIx;
    return err;
  }
  ParseError.reset = function() {
    ParseError.lines = ParseError.tokenToChar = ParseError.lineIx = undefined;
  };

  function repeat(length, str) {
    let len = length / str.length;
    let res = "";
    for(let ix = 0; ix < len; ix++) {
      res += str;
    }
    return (res.length > length) ? res.slice(0, length) : res;
  }
  function underline(startIx, length) {
    let padding = repeat(startIx, " ");
    let underline = repeat(length - 1, "~");
    return padding + "^" + underline;
  }

  function makeTokenizer(tokens:(string)[]): ((line:string) => string[]) {
    return function(raw:string) {
      let results = [];
      while(raw.length) {
        let minIx = raw.length;
        let minToken = raw;
        for(let token of tokens) {
          let ix = raw.indexOf(token);
          if(ix !== -1 && ix < minIx) {
            minIx = ix;
            minToken = token;
          }
        }

        if(minIx > 0 && minIx < raw.length) {
          results.push(raw.slice(0, minIx));
        }
        results.push(minToken);
        raw = raw.slice(minIx + minToken.length);
      }
      return results;
    };
  }

  export function tokenToString(token:Token):string {
    if(!token) return;
    let padding = token.indent ? new Array(token.indent + 1).join(" ") : "";
    if(tokenIsCalculation(token) && token.text) return token.text;
    if(tokenIsLine(token)) {
      let res = padding;
      let prev;
      for(let chunk of token.chunks) {
        if(prev && chunk.lineIx !== prev.lineIx) res += "\n";
        res += tokenToString(chunk);
        prev = chunk;
      }
      return res;
    }
    if(tokenIsField(token)) {
      if(token.value !== undefined) return `\`${token.value || ""}\``;
      return `?${token.grouped ? "?" : ""}${token.alias || ""}`;
    }
    if(tokenIsAttribute(token)) return `${padding}- ${token.property}: ${tokenToString(token.value)}`;
    if(tokenIsElement(token)) {
      let res = `${padding}${token.tag || ""}`;
      if(token.classes) res += token.classes;
      if(token.name) res += "; " + token.name;
      return res;
    }
    if(tokenIsBinding(token)) return padding + "~ " + token.text.split("\n").join("\n" + padding + "~ ");
    if(tokenIsEvent(token)) return padding + "@ " + token.event + (token.key ? ": " + tokenToString(token.key) : "");
    if(tokenIsComment(token)) return padding + ";" + token.text;
    if(tokenIsText(token) || tokenIsKeyword(token)) return padding + token.text;
    throw new Error(`Unknown token type '${token && token.type}' for token '${JSON.stringify(token)}'.`);
  }

  function consume(needles:string[], tokens:string[], err?:Error):string {
   if(needles.indexOf(tokens[0]) === -1) {
     if(err) throw err;
     return;
   }
   return tokens.shift();
  }

  function consumeWhile(needles:string[], tokens:string[], err?:Error):string {
    let res = "";
    while(true) {
      let head = tokens[0];
      if(head === undefined) {
        if(err) throw err;
        break;
      }
      if(needles.indexOf(head) === -1) break;
      res += tokens.shift();
    }
    return res;
  }

  function consumeUntil(needles:string[], tokens:string[], err?:Error):string {
    let res = "";
    while(true) {
      let head = tokens[0];
      if(head === undefined) {
        if(err) throw err;
        break;
      }
      if(needles.indexOf(head) !== -1) break;
      res += tokens.shift();
    }
    return res;
  }

  function unravel(cur:string, tangle:{[key: string]: string[]}, unraveled:string[] = []): string[] {
    unraveled.push(cur);
    for(let child of tangle[cur]) unraveled.push.apply(unraveled, unravel(child, tangle));
    return unraveled;
  }

  export function coerceInput(input) {
    if (input.match(/^-?[\d]+$/gim)) {
        return parseInt(input);
    }
    else if (input.match(/^-?[\d]+\.[\d]+$/gim)) {
        return parseFloat(input);
    }
    else if (input === "true") {
        return true;
    }
    else if (input === "false") {
        return false;
    }
    return input;
  }

  export function fingerprintSource(ast:LineAST) {
    let fingerprint = "";
    let tokenIx = 0;
    let tokenCount = ast.chunks.length;
    let head = ast.chunks[0];
    if(tokenIsText(head)) {
      if(head.text[0] === "!" && head.text[1] === " ") {
        tokenIx = 1;
        fingerprint = head.text.slice(2);
      }
    }
    for(; tokenIx < tokenCount; tokenIx++) {
      let token = ast.chunks[tokenIx];
      fingerprint +=  tokenIsField(token) ? "?" : tokenToString(token);
    }
    return fingerprint;
  }

  function getScopedBinding(alias:string, ancestors:ElementIR[], boundViews:{[id: string]: Query}): string {
    for(let ix = ancestors.length - 1; ix >= 0; ix--) {
      let parent = ancestors[ix];
      if(!parent.boundView) continue;
      let scope = boundViews[parent.boundView];
      let varId = scope.reified.aliases[alias];
      if(!varId) continue;
      let variable = scope.reified.variables[varId];
      if(!variable.selected) throw ParseError("UI Properties can only be bound to selected aliases.");
      return variable.selected;
    }
  }

  function getVariable(alias, ast:QueryIR, varId?:string, fieldId?:string) {
    if(!varId) varId = ast.aliases[alias] || Api.uuid();
    let variable = ast.variables[varId];
    if(!variable) variable = ast.variables[varId] = {selected: fieldId, alias: alias, bindings: []};
    if(!variable.selected && alias && alias[0] !== "_") variable.selected = Api.uuid();
    if(alias) ast.aliases[alias] = varId;
    return variable;
  }

  /** Parse a field in the form ?[?][alias] or a constant field in the form `[content]`. */
  function parseField(tokens:string[], tokenIx:number = 0):FieldAST|Error {
    if(consume(["?"], tokens)) {
      let field:FieldAST = {type: "field", tokenIx};
      if(consume(["?"], tokens)) field.grouped = true;
      let head = tokens[0];
      if(head && head !== " " && PUNCTUATION.indexOf(head) === -1) field.alias = tokens.shift();
      return field;

    } else if(consume(["`"], tokens)) {
      let field:FieldAST = {type: "field", tokenIx};
      let tokensLength = tokens.length;
      try {
        field.value = consumeUntil(["`"], tokens, this.parseError("Unterminated quoted literal.", field));
      } catch (err) {
        return err;
      }
      tokens.shift();
      field.value = coerceInput(field.value);
      return field;
    }
  }

  //---------------------------------------------------------------------------
  // Query Parser
  //---------------------------------------------------------------------------

  const Q_ACTION_TOKENS = ["+"];
  const Q_KEYWORD_TOKENS = ["!", "(", ")", "$=", "#", ";", "?", "`"].concat(Q_ACTION_TOKENS);
  const Q_TOKENS = [" ", "\t"].concat(Q_KEYWORD_TOKENS, PUNCTUATION);

  export class Query {
    raw: string;
    ast: QueryAST;
    reified: QueryIR;
    failed: QueryAST|QueryIR;

    errors: Error[];
    id: string;
    name: string;
    tags: string[];

    protected prev: QueryIR;
    protected lineIx: number;

    constructor(initializer?:QueryIR) {
      this.prev = initializer;
    }

    /** Nicely formatted error for reporting problems during parsing or reification. */
    parseError<T extends Token>(message:string, token:T):Error {
      let lines = this.raw.split("\n");
      if(token.lineIx) ParseError.lineIx = token.lineIx;
      else ParseError.lineIx = this.lineIx;
      ParseError.lines = lines;
      ParseError.tokenToChar = Query.tokenToChar;

      let err = ParseError(message, token);
      ParseError.reset();
      return err;
    }

    /** Convert any valid query token into its raw string equivalent. */
    stringify = tokenToString;

    /** Parse a raw query string into this Query. */
    parse(raw:string):Query {
      if(raw === this.raw) return this;
      this.errors = [];
      this.raw = raw;
      this.ast = {type: "query", chunks: []};
      this.prev = this.reified ? this.reified : this.prev;
      this.reified = undefined;

      let lines = this.raw.split("\n");
      let lineIx = 0;
      for(let line of lines) {
        let tokens = Query.tokenize(line);
        if(tokens.length === 0) {
          this.ast.chunks.push(<TextAST>{type: "text", text: "", lineIx: lineIx++});
          continue;
        }

        let maybeParsed = this.parseLine(tokens, lineIx++);
        if(maybeParsed instanceof Error) {
          this.errors.push(maybeParsed);
          continue;
        }
        let parsed = <LineAST>maybeParsed;

        if(tokenIsKeyword(parsed.chunks[0]) && parsed.chunks[0]["text"] === ";") {
          parsed["text"] = tokenToString(parsed);
          parsed.type = "comment";

        } else {
          maybeParsed = this.processAction(parsed)
            || this.processCalculation(parsed)
            || this.processOrdinal(parsed)
            || this.processSource(parsed);

          if(maybeParsed instanceof Error) {
            this.errors.push(maybeParsed);
            continue;
          }
          parsed = <LineAST>maybeParsed;
        }

        this.ast.chunks.push(parsed);
      }

      if(this.errors.length === 0) this.reify();

      return this;
    }

    /** Load an existing AST into this Query. */
    loadFromAST(ast:QueryAST, viewId?:string):Query {
      this.raw = this.prev = this.reified = undefined;
      this.errors = [];
      if(!ast) return;
      if(viewId) this.loadFromView(viewId, true);
      this.ast = ast;
      this.raw = this.stringify(this.ast);
      this.reify();
      return this;
    }

    /** Load the given view *lossily* into this Query. */
    loadFromView(viewId:string, ignoreAST:boolean = false):Query {
      this.id = viewId;
      this.name = Api.get.name(viewId);
      this.tags = Api.get.tags(viewId);
      this.raw = this.ast = this.prev = undefined;
      this.errors = [];
      this.reified = {id: viewId, sources: [], aliases: {}, variables: {}, actions: []};
      let ordinalSourceAlias:{[source:string]: string|boolean} = {};
      let bindingFieldVariable:{[id:string]: VariableIR} = {};

      // Reconstitute variables.
      for(let varId of Api.extract("variable: variable", Api.ixer.find("variable", {"variable: view": viewId}))) {
        let fieldId = (Api.ixer.findOne("select", {"select: variable": varId}) || {})["select: field"];
        let alias = Api.get.name(fieldId) || undefined;
        let variable = getVariable(alias, this.reified, varId);
        variable.selected = fieldId;
        variable.value = (Api.ixer.findOne("constant binding", {"constant binding: variable": varId}) || {})["constant binding: value"];

        let ordinalSources = Api.extract("ordinal binding: source", Api.ixer.find("ordinal binding", {"ordinal binding: variable": varId}));
        if(ordinalSources.length) {
          variable.ordinals = ordinalSources;
          for(let sourceId of ordinalSources) ordinalSourceAlias[sourceId] = alias || true;
        }

        variable.bindings = <any>Api.omit("variable", Api.humanize("binding", Api.ixer.find("binding", {"binding: variable": varId})));
        for(let binding of variable.bindings) bindingFieldVariable[binding.field] = variable;
      }

      // Reconstitute sources.
      let rawSources = {};
      for(let rawSource of Api.ixer.find("source", {"source: view": viewId})) rawSources[rawSource["source: source"]] = rawSource;
      let sourceIds = Object.keys(rawSources).sort(Api.displaySort);

      for(let sourceId of sourceIds) {
        let rawSource = rawSources[sourceId];
        let source:SourceIR = {source: sourceId, sourceView: rawSource["source: source view"], fields: []};
        this.reified.sources.push(source);

        if(ordinalSourceAlias[source.source]) source.ordinal = ordinalSourceAlias[source.source];
        if(Api.ixer.findOne("chunked source", {"chunked source: source": source.source})) source.chunked = true;
        if(Api.ixer.findOne("negated source", {"negated source: source": source.source})) source.negated = true;

        let sorted = Api.ixer.find("sorted field", {"sorted field: source": source.source});
        if(sorted && sorted.length) source.sort = <any>Api.omit("source", Api.humanize("sorted field", sorted));

        let fieldIds = Api.get.fields(source.sourceView);
        for(let fieldId of fieldIds) {
          let field:FieldIR = {field: fieldId};
          source.fields[source.fields.length] = field;
          if(Api.ixer.findOne("grouped field", {"grouped field: field": fieldId})) field.grouped = true;

          let variable = bindingFieldVariable[fieldId];
          if(variable.alias) field.alias = variable.alias;
          if(variable.value !== undefined) field.value = variable.value;
          if(variable.ordinals !== undefined && variable.ordinals.indexOf(source.source) !== -1) field.ordinal = true;
        }
      }

    if(this.errors.length === 0 && !ignoreAST) this.unreify();

      return this;
    }

    /** Unlink the reification of this Query from it's current IDs (for duplicating views). */
    unlink():Query {
      throw new Error("@TODO: Implement me.");
    }

    /** Clone this Query instance for destructive editing. */
    clone():Query {
      throw new Error("@TODO: Implement me.");
    }

    /** Retrieve the Source IR for the given view source if present. */
    getSourceIR(sourceId:string):SourceIR {
      for(let source of this.reified.sources) {
        if(source.source === sourceId) return source;
      }
    }

    /** Retrieve the Source AST for the given source IR if present. */
    getSourceAST(source:SourceIR) {
      if(source.lineIx === undefined) return;
      return this.ast.chunks[source.lineIx];
    }

    protected static tokenize = makeTokenizer(Q_TOKENS);
    protected static tokenToChar(token:Token, line:string):number {
      return (token.tokenIx !== undefined) ? Query.tokenize(line).slice(0, token.tokenIx - 1).join("").length : 0;
    }

    /** Parse a line's worth of tokens into field, keyword, and structure chunks. */
    protected parseLine(tokens:string[], lineIx:number = 0):LineAST|Error {
      this.lineIx = lineIx;
      let tokensLength = tokens.length;
      let padding = consumeWhile([" ", "\t"], tokens);
      let ast:LineAST = {type: "", chunks: [], lineIx, indent: padding.length};
      while(tokens.length) {
        let tokenIx = tokensLength - tokens.length + 1;
        let token = this.parseField(tokens, tokenIx)
          || this.parseKeyword(tokens, tokenIx)
          || this.parseStructure(tokens, tokenIx);
        if(!token) return this.parseError("Unrecognized token sequence.", {type: "text", text: tokens.join(""), tokenIx, lineIx});
        if(token instanceof Error) return token;
        else ast.chunks.push(<Token>token);
      }
      return ast.chunks.length ? ast : undefined;
    }

    protected parseField = parseField;

    /** Parse a single keyword token from Q_KEYWORD_TOKENS. */
    protected parseKeyword(tokens:string[], tokenIx:number = 0) {
      if(Q_KEYWORD_TOKENS.indexOf(tokens[0]) !== -1) {
        return {type: "keyword", text: tokens.shift(), tokenIx};
      }
    }

    /** consume tokens into a single text chunk until a Q_KEYWORD_TOKEN is hit. */
    protected parseStructure(tokens:string[], tokenIx:number = 0) {
      let text = consumeUntil(Q_KEYWORD_TOKENS, tokens);
      if(text) return {type: "text", text, tokenIx};
    }

    protected processAction(line:LineAST):LineAST|Error {
      let kw = line.chunks[0];
      if(line.chunks.length < 1 || !tokenIsKeyword(kw) || Q_ACTION_TOKENS.indexOf(kw.text) === -1) return;
      line.type = "action";
      for(let token of line.chunks) {
        if(tokenIsField(token) && !token.alias) return this.parseError("All action fields must be aliased to a query field.", token);
      }

      return line;
    }

    protected processCalculation(line:LineAST):CalculationAST|Error {
      let calculations:{view:string, calculation:string}[] = [];
      let fields:{calculation:string, field:string, ix:number}[] = [];

      let kw = line.chunks[2];
      if(line.chunks.length < 3 || !tokenIsKeyword(kw) || kw.text !== "$=") return;
      let text = line.chunks.map(this.stringify).join("");

      if(!tokenIsField(line.chunks[0])) return this.parseError("Calculations must be formatted as '?field $= <calculation>", line);
      let resultField:FieldAST = line.chunks[0];

      let partIx = 0;
      let lines:CalculationAST[] = [];
      let stack = [{type: "source", chunks: [], partIx: partIx++, lineIx: line.lineIx}];
      for(let token of line.chunks.slice(4)) {
        let cur = stack[stack.length - 1];

        if(tokenIsKeyword(token)) {
          if(token.text === "(") {
            stack.push({type: "source", chunks: [], partIx: partIx++, lineIx: line.lineIx});
            continue;
          }
          else if(token.text === ")") {
            stack.pop();
            let prev = stack[stack.length - 1];
            if(!prev) return this.parseError("Too many close parens", line);
            let alias = `_${resultField.alias}-${cur.partIx}`;
            let field:FieldAST = {type: "field", alias};
            cur.chunks.push({type: "text", text: " = "}, field);
            prev.chunks.push(field);
            lines.push(cur);
            continue;
          }
        }
        cur.chunks.push(token);
      }
      if(stack.length > 1) return this.parseError("Too few close parens", line);
      if(stack.length === 0) return this.parseError("Too many close parens", line);
      stack[0].chunks.push({type: "text", text: " = "}, resultField);
      lines.push(stack[0]);

      return {type: "calculation", chunks: lines, text};
    }

    protected processOrdinal(parsedLine:LineAST):OrdinalAST|Error {
      let kw = parsedLine.chunks[0];
      if(parsedLine.chunks.length < 1 || !tokenIsKeyword(kw) || kw.text !== "#") return;
      parsedLine.type = "ordinal";

      let prevChunk = this.ast.chunks[this.ast.chunks.length - 1];
      if(!prevChunk || !tokenIsSource(prevChunk))
        return this.parseError("Ordinal must immediately follow a source.", {type: "text", text: parsedLine.chunks.map(this.stringify).join("")});
      if(parsedLine.chunks.length < 3 || !tokenIsField(parsedLine.chunks[2]))
        return this.parseError("Ordinal requires a field to bind to ('?' or '?foo').", parsedLine.chunks[2]);
      if(parsedLine.chunks.length > 3 && parsedLine.chunks[3]["text"].indexOf("by") !== 1)
        return this.parseError("Ordinals are formatted as '# ? by ?... <dir>'", parsedLine.chunks[3]);

      let line = <OrdinalAST>parsedLine;
      line.alias = line.chunks[2]["alias"];
      line.directions = [];
      let sortFieldCount = 0;
      for(let tokenIx = 3, chunkCount = line.chunks.length; tokenIx < chunkCount; tokenIx++) {
        let token = line.chunks[tokenIx];
        if(tokenIsField(token)) {
          if(!token.alias) return this.parseError("Ordinal sorting fields must be aliased to a query field.", token);
          sortFieldCount++;
        } else if(tokenIsText(token) && sortFieldCount > 0) {
          let text = token.text.trim().toLowerCase();
          if(text.indexOf("ascending") === 0) line.directions[sortFieldCount - 1] = "ascending";
          else if(text.indexOf("descending") === 0) line.directions[sortFieldCount - 1] = "descending";
        }
      }
      if(sortFieldCount === 0)
        return this.parseError("Ordinal requires at least one sorting field.", {type: "text", text: parsedLine.chunks.map(this.stringify).join("")});

      return line;
    }

    protected processSource(line:SourceAST):SourceAST {
      let kw = line.chunks[0];
      if(tokenIsText(kw) && kw.text === "!") line.negated = true;
      line.type = "source";
      return line;
    }

    protected reify() {
      if(!this.ast) return;
      if(this.reified) this.prev = this.reified;
      this.reified = {id: (this.prev && this.prev.id) || Api.uuid(), sources: [], aliases: {}, variables: {}, actions: []};
      let prev = this.prev;

      let sort = [];
      let chunks = [];
      for(let line of this.ast.chunks) {
        if(tokenIsCalculation(line)) chunks.push.apply(chunks, line.chunks);
        else chunks.push(line);
      }

      LINE_LOOP: for(let line of chunks) {
        this.lineIx = line.lineIx;
        if(tokenIsSource(line)) {
          let fingerprint = fingerprintSource(line);
          let {"view fingerprint: view": view} = Api.ixer.findOne("view fingerprint", {"view fingerprint: fingerprint": fingerprint}) || {};
          if(!view) {
            this.errors.push(this.parseError(`Fingerprint '${fingerprint}' matches no known views.`, line)); //@NOTE: Should this create a union..?
            continue;
          }

          let sourceId = Api.uuid();
          let prevSource = prev && prev.sources[this.reified.sources.length];
          if(prevSource && prevSource.sourceView === view) sourceId = prevSource.source;

          let source:SourceIR = {negated: line.negated, source: sourceId, sourceView: view, fields: [], lineIx: line.lineIx};
          let fieldIxes = Api.ixer.find("fingerprint field", {"fingerprint field: fingerprint": fingerprint}).slice()
            .sort((a, b) => a["fingerprint field: ix"] - b["fingerprint field: ix"]);

          for(let token of line.chunks) {
            if(tokenIsField(token)) {
              let {"fingerprint field: field": fieldId} = fieldIxes.shift() || {};
              if(!fieldId) {
                this.errors.push(this.parseError(`Fingerprint '${fingerprint}' is missing for field.`, token));
                break LINE_LOOP;
              }
              let field = {field:fieldId, grouped: token.grouped, alias: token.alias, value: token.value};
              source.fields.push(field);

              let varId = prev && prev.aliases[field.alias];
              let variable = getVariable(field.alias, this.reified, varId, varId && prev.variables[varId].selected);
              if(field.grouped) source.chunked = true;
              if(field.value !== undefined) variable.value = field.value;
              variable.bindings.push({source: source.source, field: field.field});
            }
          }
          this.reified.sources.push(source);

        } else if(tokenIsOrdinal(line)) {
          let source = this.reified.sources[this.reified.sources.length - 1];
          if(!source) {
            this.errors.push(this.parseError(`Ordinals must follow a valid source.`, line));
            continue;
          }
          source.ordinal = line.alias || true;
          let varId = prev && prev.aliases[line.alias];
          let variable = getVariable(line.alias, this.reified, varId, varId && prev.variables[varId].selected);
          if(!variable.ordinals) variable.ordinals = [source.source];
          else variable.ordinals.push(source.source);
          let unsorted = [];
          for(let field of source.fields) unsorted[unsorted.length] = field.field;

          let sortFieldIx = 0;
          source.sort = [];
          for(let tokenIx = 3, chunkCount = line.chunks.length; tokenIx < chunkCount; tokenIx++) {
            let chunk = line.chunks[tokenIx];
            if(tokenIsField(chunk)) {
              let matched = false;
              for(let field of source.fields) {
                if(field.alias !== chunk.alias) continue;
                source.sort.push({ix: sortFieldIx, field: field.field, direction: line.directions[sortFieldIx++] || "ascending"});
                unsorted.splice(unsorted.indexOf(field.field), 1);
                matched = true;
                break;
              }
              if(!matched) {
                this.errors.push(this.parseError(`Ordinal alias '${chunk.alias}' does not match any aliased fields of the ordinated source.`, chunk));
              }
            }
          }
          for(let fieldId of unsorted) source.sort.push({ix: sortFieldIx++, field: fieldId, direction: "ascending"});

        } else if(tokenIsAction(line)) {
          this.errors.push(this.parseError("@TODO: Add support for reifying actions", line));
          continue;
        }
      }

      if(this.errors.length) {
        this.failed = this.reified;
        this.reified = undefined;
      } else this.id = this.reified.id;
    }

    protected unreify() {
      if(!this.reified) return;

      this.ast = {type: "query", chunks: []};
      for(let source of this.reified.sources) {
        // @FIXME: This may not be the correct fingerprint, we need to look through all of them
        // comparing field ordering. This still isn't lossless, but it's at least better.
        let {"view fingerprint: fingerprint": fingerprint} = Api.ixer.findOne("view fingerprint", {"view fingerprint: view": source.sourceView}) ||{};
        if(!fingerprint) throw new Error(`No fingerprint found for view '${source.sourceView}'.`);
        let structures = fingerprint.split("?");
        let tail:string = structures.pop(); // We don't want to tack an extra field to the end of the fingerprint.
        let line:SourceAST = {type: "source", negated: source.negated, chunks: [], lineIx: this.ast.chunks.length};
        this.ast.chunks.push(line);

        let fieldIx = 0;
        for(let text of structures) {
          // Add structure token if there's any text.
          if(text) line.chunks.push(<TextAST>{type: "text", text});

          // Add field token between this structure token and the next.
          let field:FieldIR = source.fields[fieldIx++];
          line.chunks.push(<FieldAST>{type: "field", alias: field.alias, grouped: field.grouped, value: field.value});
        }
        if(tail) line.chunks.push(<TextAST>{type: "text", text: tail});

        if(source.ordinal) {
          let line:OrdinalAST = {type: "ordinal", alias: undefined, directions: [], chunks: [], lineIx: this.ast.chunks.length};
          this.ast.chunks.push(line);

          if(source.ordinal !== true) line.alias = <string>source.ordinal;
          line.chunks[0] = <TextAST>{type: "text", text: `# ?${line.alias || ""} by `}; // @NOTE: Shouldn't line.alias be a field?

          let fields = {};
          for(let field of source.fields) fields[field.field] = field;

          // Super lossy, but nothing can be done about it.
          for(let {ix, field:fieldId, direction} of source.sort) {
            let field = fields[fieldId];
            line.chunks.push(<FieldAST>{type: "field", alias: field.alias, grouped: field.grouped, value: field.value});
            line.chunks.push(<TextAST>{type: "text", text: ` ${direction} `});
            line.directions.push(direction);
          }
        }
      }

      if(this.reified.actions.length) throw new Error("@TODO Implement action unreification.");

      if(!this.errors.length) this.raw = this.stringify(this.ast);
    }
  }

  //---------------------------------------------------------------------------
  // Ui Parser
  //---------------------------------------------------------------------------
  const U_TOKENS = [";", ":", "@", "~", "-", " ", "\t", "`", "?"];
  export class Ui {
    raw: string;
    ast: UiAST;
    reified: UiIR;
    failed: UiAST|UiIR;

    errors: Error[];
    id: string;
    name: string;
    tags: string[];

    protected prev: UiIR;
    protected lineIx: number;

    constructor(initializer?:UiIR) {
      this.prev = initializer;
    }

    /** Nicely formatted error for reporting problems during parsing or reification. */
    parseError<T extends Token>(message:string, token?:T):Error {
      let lines = this.raw.split("\n");
      if(!token) token = <any>{type: "text", text: lines[this.lineIx]};
      if(token.lineIx) ParseError.lineIx = token.lineIx;
      else ParseError.lineIx = this.lineIx;
      ParseError.lines = lines;
      ParseError.tokenToChar = Ui.tokenToChar;

      let err = ParseError(message, token);
      ParseError.reset();
      return err;
    }

    /** Convert any valid query token into its raw string equivalent. */
    stringify = tokenToString;

     /** Parse a raw ui string into this Ui. */
    parse(raw:string):Ui {
      if(raw === this.raw) return this;
      this.errors = [];
      this.raw = raw;
      this.ast = {type: "ui", chunks: []};
      this.prev = this.reified ? this.reified : this.prev;
      this.reified = undefined;

      let lines = raw.split("\n");
      this.lineIx = -1;
      for(let rawLine of lines) {
        this.lineIx++;
        let tokens = Ui.tokenize(rawLine);
        let tokensLength = tokens.length;
        let consumed = consumeWhile([" ", "\t"], tokens);
        let indent = tokensLength - tokens.length;
        let line:Token = {type: "", lineIx: this.lineIx, indent};
        let head = tokens.shift();

        if(head === undefined) {
          this.ast.chunks.push(<TextAST>{type: "text", text: "", lineIx: this.lineIx, indent});
          continue;
        }
        if(head === ";") line.type = "comment";
        else if(head === "-") line.type = "attribute";
        else if(head === "~") line.type = "binding";
        else if(head === "@") line.type = "event";
        else line.type = "element";

        if(tokenIsComment(line)) {
          line.text = tokens.join("");

        } else if(tokenIsAttribute(line)) {
          consumeWhile([" ", "\t"], tokens);
          try {
            line.property = consumeUntil([":"], tokens, this.parseError("Attributes are formatted as '- <property>: <value or field>'."));
          } catch(err) {
            this.errors.push(err);
            continue;
          }
          tokens.shift();
          consumeWhile([" ", "\t"], tokens);
          let field = this.parseField(tokens, tokensLength - tokens.length);
          // @TODO we can wrap this in `` and rerun it, or skip the middleman since we know the value.
          if(!field) {
            this.errors.push(this.parseError("Value of attribute must be a field (either ' ?foo ' or ' `100` ')",
              {type: "text", text: tokens.join(""), tokenIx: tokensLength - tokens.length}));
            continue;
          }
          if(field instanceof Error) {
            this.errors.push(field);
            continue;
          }
          line.value = <FieldAST>field;
          line.static = line.value.value !== undefined;
          if(tokens.length) {
            this.errors.push(
              this.parseError("Extraneous tokens after value.", {type: "text", text: tokens.join(""), tokenIx: tokensLength - tokens.length})
            );
            continue;
          }

        } else if(tokenIsBinding(line)) {
          let prevLine = this.ast.chunks[this.ast.chunks.length - 1];
          if(!prevLine || tokenIsElement(prevLine)) {
            consumeWhile([" ", "\t"], tokens);
            line.text = tokens.join("");

          } else if(tokenIsBinding(prevLine)) {
            consumeWhile([" ", "\t"], tokens);
            prevLine.text += "\n" + tokens.join("");
            continue;

          } else {
            line.text = tokens.join("");
            this.errors.push(this.parseError("Binding must immediately follow an element or a binding.", line));
            continue;
          }

        } else if(tokenIsEvent(line)) {
          consumeWhile([" ", "\t"], tokens);
          line.event = consumeUntil([":"], tokens);
          tokens.shift();
          consumeWhile([" ", "\t"], tokens);
          if(tokens.length) {
            let field = this.parseField(tokens, tokensLength - tokens.length);
            // @TODO we can wrap this in `` and rerun it, or skip the middleman since we know the value.
            if(!field) {
              this.errors.push(this.parseError("Value of attribute must be a field (either ' ?foo ' or ' `100` ')",
                {type: "text", text: tokens.join(""), tokenIx: tokensLength - tokens.length}));
              continue;
            }
            if(field instanceof Error) {
              this.errors.push(field);
              continue;
            }
            line.key = <FieldAST>field;
            if(!line.key.alias) {
              this.errors.push(this.parseError("Event keys must be aliased to a bound field.", line.key));
              continue;
            }
          }
          if(tokens.length) {
            this.errors.push(
              this.parseError("Extraneous tokens after value.", {type: "text", text: tokens.join(""), tokenIx: tokensLength - tokens.length})
            );
            continue;
          }

        } else if(tokenIsElement(line)) {
          if(head) line.tag = head;
          line.classes = consumeUntil([";"], tokens);
          if(tokens[0] === ";") tokens.shift();
          if(tokens.length) line.name = tokens.join("").trim();
        }

        this.ast.chunks.push(line);
      }
      if(this.errors.length === 0) this.reify();
    }

    /** Load an existing AST into this Ui. */
    loadFromAST(ast:QueryAST, elemId?:string):Ui {
      this.raw = this.prev = this.reified = undefined;
      this.errors = [];
      if(!ast) return;
      if(elemId) this.loadFromElement(elemId, true);
      this.ast = ast;
      this.raw = this.stringify(this.ast);
      this.reify();
      return this;
    }

    /** Load the given element *lossily* into this Ui. */
    loadFromElement(rootId:string, ignoreAST:boolean = false):Ui {
      this.id = rootId;
      this.name = Api.get.name(rootId);
      this.tags = Api.get.tags(rootId);
      this.raw = this.ast = this.prev = undefined;
      this.errors = [];

      let root:ElementIR = Api.humanize("uiElement", Api.ixer.findOne("uiElement", {"uiElement: element": rootId}));
      if(!root) throw new Error(`Requested element '${rootId}' does not exist.`);
      this.reified = {elements: [], root, boundQueries: {}};

      let queries = [];
      let elems = [root];
      while(elems.length) {
        let elem = elems.shift();
        let elemId = elem.element;
        elem.attributes = {};
        elem.boundAttributes = {};
        elem.name = Api.get.name(elemId) || undefined;

        let boundView:string = (Api.ixer.findOne("uiElementBinding", {"uiElementBinding: element": elemId}) || {})["uiElementBinding: view"];
        if(boundView) queries.push(elem.boundView = boundView);

        let attrs = Api.ixer.find("uiAttribute", {"uiAttribute: element": elemId});
        for(let {"uiAttribute: property": prop, "uiAttribute: value": val} of attrs) elem.attributes[prop] = val;

        let boundAttrs = Api.ixer.find("uiAttributeBinding", {"uiAttributeBinding: element": elemId});
        for(let {"uiAttributeBinding: property": prop, "uiAttributeBinding: field": field} of boundAttrs) elem.boundAttributes[prop] = field;

        let bindings = Api.ixer.find("uiScopedBinding", {"uiScopedBinding: element": elemId});
        if(bindings.length) {
          elem.bindings = {};
          for(let {"uiScopedBinding: field": field, "uiScopedBinding: scoped field": scopedField} of bindings) elem.bindings[field] = scopedField;
        }

        let children = Api.ixer.find("uiElement", {"uiElement: parent": elemId});
        for(let elem of Api.humanize("uiElement", children)) elems.push(elem);

        if(elem !== root) this.reified.elements.push(elem);
      }

      for(let queryId of queries) this.reified.boundQueries[queryId] = new Query().loadFromView(queryId);

      if(this.errors.length === 0 && !ignoreAST) this.unreify();
      return this;
    }

    protected static tokenize = makeTokenizer(U_TOKENS);
    protected static tokenToChar(token:Token, line:string):number {
      return (token.tokenIx !== undefined) ? Ui.tokenize(line).slice(0, token.tokenIx - 1).join("").length : 0;
    }

    protected parseField = parseField;

    protected reify() {
      if(!this.ast) return;
      if(this.reified) this.prev = this.reified;
      let prev = this.prev;

      let rootId = prev ? prev.root.element : Api.uuid();
      let root:ElementIR = {element: rootId, tag: "div", ix: 0, attributes: {}, boundAttributes: {}, events: [], boundEvents: {}};
      this.reified = {elements: [], root, boundQueries: {}};
      let indent = {[root.element]: -1};
      let childCount = {[root.element]: 0};
      let ancestors = [root];

      for(let line of this.ast.chunks) {
        if(tokenIsComment(line) || tokenIsText(line)) continue;

        let parentElem:ElementIR;
        while(ancestors.length) {
            parentElem = ancestors[ancestors.length - 1];
            if(indent[parentElem.element] < line.indent) break;
            ancestors.pop();
        }

        if(tokenIsElement(line)) {
          let prevElem = prev && prev.elements[this.reified.elements.length]; // This is usually not going to match up.
          let elemId = prevElem ? prevElem.element : Api.uuid();
          let ix = childCount[parentElem.element]++;
          let elem:ElementIR = {element: elemId, tag: line.tag, parent: parentElem.element, ix, attributes: {}, boundAttributes: {}, events: [], boundEvents: {}};
          indent[elem.element] = line.indent;
          childCount[elem.element] = 0;
          ancestors.push(elem);

          if(line.classes) elem.attributes["c"] = line.classes;
          if(line.name) elem.name = line.name;
          this.reified.elements.push(elem);

        } else if(tokenIsBinding(line)) {
          if(!parentElem) {
            this.errors.push(this.parseError("Bindings must follow an element.", line));
            continue;
          }
          let query = new Query().parse(line.text);
          // @TODO: Use prev.queries for mapping queryIRs

          this.reified.boundQueries[query.id] = query;
          parentElem.boundView = query.id;

          let joinedFields = {};
          let scopeJoined = false;
          for(let alias in query.reified.aliases) {
            let scopedField = getScopedBinding(alias, ancestors.slice(0, -1), this.reified.boundQueries);
            let selected = query.reified.variables[query.reified.aliases[alias]].selected;
            if(!scopedField) continue;
            if(!selected) {
              this.errors.push(this.parseError(`Cannot join nested views on unselected alias '${alias}'`));
              continue;
            }
            joinedFields[selected] = scopedField;
            scopeJoined = true;
          }
          if(scopeJoined) parentElem.bindings = joinedFields;

        } else if(tokenIsAttribute(line)) {
          if(!parentElem) {
            this.errors.push(this.parseError("Attributes must follow an element.", line));
            continue;
          }

          if(line.static) {
            if(line.property === "parent") parentElem.parent = line.value.value;
            else if(line.property === "id") {
              let old = parentElem.element;
              if(childCount[old]) throw ParseError("ID must be set prior to including child elements.");
              parentElem.element = line.value.value;
              indent[parentElem.element] = indent[old];
              childCount[parentElem.element] = childCount[old];
            }
            else parentElem.attributes[line.property] = line.value.value;
          } else {
            parentElem.boundAttributes[line.property] = getScopedBinding(line.value.alias, ancestors, this.reified.boundQueries);
            if(!parentElem.boundAttributes[line.property]) {
              this.errors.push(this.parseError(`Could not resolve alias '${line.value.alias}' for bound attribute '${line.property}'`));
              continue;
            }
          }
        } else if(tokenIsEvent(line)) {
          if(line.key) {
            parentElem.boundEvents[line.event] = getScopedBinding(line.key.alias, ancestors, this.reified.boundQueries);
              if(!parentElem.boundEvents[line.event]) {
                this.errors.push(this.parseError(`Could not resolve alias '${line.key.alias}' for bound event '${line.event}'`));
                continue;
              }
          } else {
            parentElem.events.push(line.event);
          }
        }
      }

      if(this.errors.length !== 0) {
        this.failed = this.reified;
        this.reified = undefined;
      } else this.id = rootId;
    }

    protected unreify() {
      if(!this.reified) return;
      this.ast = {type: "ui", chunks: []};
      let aliases:{[field:string]: string} = {};

      // Naive dependency resolution.
      let childMap:{[key:string]: string[]} = {[this.reified.root.element]: []};
      let elemMap:{[key:string]: ElementIR} = {[this.reified.root.element]: this.reified.root};
      for(let elem of this.reified.elements) {
        if(!childMap[elem.parent]) childMap[elem.parent] = [];
        childMap[elem.parent].push(elem.element);
        childMap[elem.element] = [];
        elemMap[elem.element] = elem;
      }
      let elems = unravel(this.reified.root.element, childMap);

      let elemIndent = {[this.reified.root.element]: -2};
      for(let elemId of elems) {
        let elem = elemMap[elemId];
        let indent = 0;
        if(elem.parent) indent = elemIndent[elem.element] = elemIndent[elem.parent] + 2;

        let elemAST:ElementAST = {type: "element", name: elem.name, tag: elem.tag, indent, lineIx: this.ast.chunks.length};
        if(elem !== this.reified.root) this.ast.chunks.push(elemAST);
        else {
          indent = -2;
          elemAST = undefined;
        }

        if(elem.boundView) {
          let queryString = this.reified.boundQueries[elem.boundView].raw.trim();
          let line:BindingAST = {type: "binding", text: queryString, indent: indent + 2, lineIx: this.ast.chunks.length};
          this.ast.chunks.push(line);

          if(elem.bindings) {
            for(let fieldId in elem.bindings) {
              let scopeId = elem.bindings[fieldId];
              aliases[fieldId] = aliases[scopeId];
            }
          }
        }

        for(let property in elem.attributes) {
          if(property === "c" && elemAST) {
            elemAST.classes = elem.attributes[property];
            continue;
          }
          let value = {type: "field", value: elem.attributes[property]};
          let line:AttributeAST = {type: "attribute", property, value, static: true, indent: indent + 2, lineIx: this.ast.chunks.length};
          this.ast.chunks.push(line);
        }
        for(let property in elem.boundAttributes) {
          let fieldId = elem.boundAttributes[property];
          let alias = aliases[fieldId];
          if(!alias) {
            let base = Api.get.name(fieldId);

            if(!base) throw new Error(`@TODO: Generate alias for unnamed fields. '${fieldId}'`);
            let ix = 0;

            do {
              var dup = false;
              alias = base + (ix ? `-${ix}` : "");
              for(let curFieldId in aliases) {
                if(aliases[curFieldId] === alias) {
                  dup = true;
                  break;
                }
              }
              ix++;
            } while(dup);
            aliases[fieldId] = alias;
          }

          let value:FieldAST = {type: "field", alias};
          let line:AttributeAST = {type: "attribute", property, value, static: false, indent: indent + 2, lineIx: this.ast.chunks.length};
          this.ast.chunks.push(line);
        }
      }

      if(this.errors.length === 0) this.raw = this.stringify(this.ast);
    }
  }
}