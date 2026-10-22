#pragma once
// JSON grammar adapted from the pinned QVAC grammars/json.gbnf (MIT).
// QVAC's license remains in the distributed runtime notices/source bundle.
// Grammar controls syntax only. Context still validates claims and quotations.
namespace antisocial_ai {
inline constexpr const char * json_object_grammar = R"gbnf(
root ::= ws object
value ::= object | array | string | number | ("true" | "false" | "null") ws
object ::= "{" ws (string ":" ws value ("," ws string ":" ws value)*)? "}" ws
array ::= "[" ws (value ("," ws value)*)? "]" ws
string ::= "\"" ([^"\\\x7F\x00-\x1F] | "\\" (["\\/bfnrt] | "u" [0-9a-fA-F]{4}))* "\"" ws
number ::= "-"? ("0" | [1-9] [0-9]*) ("." [0-9]+)? ([eE] [-+]? [0-9]+)? ws
ws ::= [ \t\n\r]{0,16}
)gbnf";
}
