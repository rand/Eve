use std;
use std::iter::IntoIterator;

use value::{Value, ToValue, Tuple, Relation};
use interpreter;
use interpreter::{EveFn,ToExpression};

#[derive(PartialEq, Eq, Clone, Debug)]
pub enum ConstraintOp {
    LT,
    LTE,
    EQ,
    NEQ,
    GT,
    GTE,
}

#[derive(Clone, Debug)]
pub enum Ref {
    Constant{value: Value},
    Value{clause: usize, column: usize},
    Tuple{clause: usize},
    Relation{clause: usize},
    Call{clause: usize},
}

pub trait ToRef { 
    fn to_constref(self) -> Ref; 
    fn to_callref(self) -> Ref;
    fn to_valref(self) -> Ref;
}

impl ToRef for f64 { 
    fn to_constref(self) -> Ref { Ref::Constant{value: self.to_value() } }
    fn to_callref(self) -> Ref { panic!("Cannot convert f64 to CallRef"); } 
    fn to_valref(self) -> Ref { panic!("Cannot convert f64 to ValRef"); } 
}

impl<'a> ToRef for &'a str {
    fn to_constref(self) -> Ref { Ref::Constant{value: self.to_value() } }
    fn to_callref(self) -> Ref { panic!("Cannot convert &str to CallRef"); } 
    fn to_valref(self) -> Ref { panic!("Cannot convert &str to ValRef"); } 
}

impl ToRef for i32 { 
    fn to_constref(self) -> Ref { Ref::Constant{value: self.to_value() } }
    fn to_callref(self) -> Ref { Ref::Call{clause: self as usize } }
    fn to_valref(self) -> Ref { panic!("Cannot convert i32 to ValRef"); }
}

impl ToRef for (i32,i32) {
    fn to_constref(self) -> Ref { panic!("Cannot convert (i32,i32) to ConstRef"); }
    fn to_callref(self) -> Ref { panic!("Cannot convert (i32,i32) to CallRef"); }
    fn to_valref(self) -> Ref { match self { (a,b) => Ref::Value{clause: a as usize, column: b as usize}, } } 
}

impl ToRef for (usize,usize) {
    fn to_constref(self) -> Ref { panic!("Cannot convert (usize,usize) to ConstRef"); }
    fn to_callref(self) -> Ref { panic!("Cannot convert (usize,usize) to CallRef"); }
    fn to_valref(self) -> Ref { match self { (a,b) => Ref::Value{clause: a, column: b}, } } 
}


impl ToRef for Value {
    fn to_constref(self) -> Ref { 
        match self {
            Value::Float(_) => Ref::Constant{value: self},
            _ => panic!("Cannot convert Value to ConstRef"),
        }
    }
    fn to_callref(self) -> Ref { panic!("Cannot convert Value to CallRef"); }
    fn to_valref(self) -> Ref { panic!("Cannot convert Value to ValRef"); }
}

impl Ref {
    fn resolve<'a>(&'a self, result: &'a Vec<Value>) -> &'a Value {

        match *self {
            Ref::Constant{ref value} => {
                value
            },
            Ref::Value{clause, column} => {
                match result[clause] {
                    Value::Tuple(ref tuple) => {
                        &tuple[column]
                    },
                    _ => panic!("Expected a tuple"),
                }
            },
            Ref::Tuple{clause} => {
                let value = &result[clause];
                match *value {
                    Value::Tuple(..) => value,
                    _ => panic!("Expected a tuple"),
                }
            },
            Ref::Relation{clause} =>{
                let value = &result[clause];
                match *value {
                    Value::Relation(..) => value,
                    _ => panic!("Expected a relation"),
                }
            },
            Ref::Call{clause} => {
                let value = &result[clause];
                match *value {
                    Value::Float(..) => value,
                    Value::String(..) => value,
                    _ => panic!("Expected a value"),
                }
            },
        }
    }
}

#[derive(Clone, Debug)]
pub struct Constraint {
    pub my_column: usize,
    pub op: ConstraintOp,
    pub other_ref: Ref,
}

impl Constraint {

    // Resolves the RHS reference in the constraint to a value
    fn prepare<'a>(&'a self, result: &'a Vec<Value>) -> &'a Value {
        self.other_ref.resolve(result)
    }

    // bearing in mind that Value is only PartialOrd so this may do weird things with NaN
    fn test(&self, my_row: &Vec<Value>, other_value: &Value) -> bool {
        let my_value = &my_row[self.my_column];
        match self.op {
            ConstraintOp::LT => my_value < other_value,
            ConstraintOp::LTE => my_value <= other_value,
            ConstraintOp::EQ => my_value == other_value,
            ConstraintOp::NEQ => my_value != other_value,
            ConstraintOp::GT => my_value > other_value,
            ConstraintOp::GTE => my_value >= other_value,
        }
    }
}

#[derive(Clone, Debug)]
pub struct Source {
    pub relation: usize,
    pub constraints: Vec<Constraint>,
}

impl Source {
    fn constrained_to(&self, inputs: &Vec<&Relation>, result: &Vec<Value>) -> Relation {
   
        let prepared: Vec<&Value> = self.constraints.iter()
                                                    .map(|constraint| constraint.prepare(result))
                                                    .collect();
        let input = inputs[self.relation];
        input.iter().filter(|row| self.constraints
                                      .iter()
                                      .zip(prepared.iter())
                                      .all(|(constraint, value)| constraint.test(row, value) ) ) 
                    .map(|row| row.clone())
                    .collect()
    }
}

#[derive(Clone)]
pub struct Call {
    pub fun: EveFn,
    pub arg_refs: Vec<Ref>,
}

impl std::fmt::Debug for Call {
    fn fmt(&self, formatter: &mut std::fmt::Formatter) -> Result<(), std::fmt::Error> {
        formatter.write_str(&*format!("Call{{fun: <fun>, arg_refs:{:?}}}", self.arg_refs))
    }
}

impl Call {
    fn eval(&self, result: &Vec<Value>) -> Value {

        let args: Vec<Value> = self.arg_refs.iter().map(|arg_ref| arg_ref.resolve(result).clone()).collect();

        // Convert to an expression vector... this should be streamlined... make interpreter::Call take a value vector?
        let mut eargs = interpreter::ExpressionVec::new();
        for arg in args {
            eargs.push(arg.to_expr());
        }
        let c4 = interpreter::build_call(self.fun.clone(),eargs);

        interpreter::calculate(&c4.to_expr())

    }
}


#[derive(Clone, Debug)]
pub enum Clause {
    Tuple(Source),
    Relation(Source),
    Call(Call),
}

impl Clause {
    fn constrained_to(&self, inputs: &Vec<&Relation>, result: &Vec<Value>) -> Vec<Value> {
        match *self {
            Clause::Tuple(ref source) => {
                let relation = source.constrained_to(inputs, result);
                relation.into_iter().map(|tuple| Value::Tuple(tuple)).collect()
            },
            Clause::Relation(ref source) => {
                let relation = source.constrained_to(inputs, result);
                vec![Value::Relation(relation)]
            },
            Clause::Call(ref call) => {
                let value = call.eval(result);
                vec![value]
            }
        }
    }
}

pub trait ToClause { fn to_clause(self) -> Clause; }

impl ToClause for Clause { fn to_clause(self) -> Clause { self } } 
impl ToClause for Call { fn to_clause(self) -> Clause { Clause::Call(self) } }

// Macro for creating clause vectors
#[macro_export]
macro_rules! clausevec {
    ( $( $x:expr ),* ) => {
        {
            let mut temp_vec = Vec::new();
            $(
                temp_vec.push($x.to_clause());
            )*
            temp_vec
        }
    };
}

#[derive(Clone, Debug)]
pub struct Query {
    pub clauses: Vec<Clause>,
}

// an iter over results of the query, where each result is either:
// * a `max_len` tuple indicating a valid result
// * a smaller tuple indicating a backtrack point
pub struct QueryIter<'a> {
    query: &'a Query,
    inputs: Vec<&'a Relation>,
    max_len: usize, // max length of a result
    now_len: usize, // ixes[0..now_len] and values[0..now_len] are all valid for the next result
    has_next: bool, // are there any more results to be found
    ixes: Vec<usize>, // index of the value last returned by each clause
    values: Vec<Vec<Value>>, // the constrained relations representing each clause
}

impl Query {
    pub fn iter<'a>(&'a self, inputs: Vec<&'a Relation>) -> QueryIter {
        let max_len = self.clauses.len();
        QueryIter{
            query: &self,
            inputs: inputs,
            max_len: max_len,
            now_len: 0,
            has_next: true, // can always return at least the early fail
            ixes: vec![0; max_len],
            values: vec![vec![]; max_len]
        }
    }
}

impl<'a> Iterator for QueryIter<'a> {
    type Item = Tuple;

    fn next(&mut self) -> Option<Tuple> {
        if !self.has_next { return None };

        let mut result = vec![];

        // set known values
        for i in (0 .. self.now_len) {
            result.push(self.values[i][self.ixes[i]].clone());
        }

        // determine the values that changed since last time
        for i in (self.now_len .. self.max_len) {
            let values = self.query.clauses[i].constrained_to(&self.inputs, &result);
            if values.len() == 0 {
                break;
            } else {
                self.now_len = i + 1;
                result.push(values[0].clone());
                self.values[i] = values;
                self.ixes[i] = 0;
            }
        }

        // see if there is a next result
        self.has_next = false;
        for i in (0 .. self.now_len).rev() {
            let ix = self.ixes[i] + 1;
            if ix < self.values[i].len() {
                self.ixes[i] = ix;
                self.has_next = true;
                self.now_len = i + 1;
                break;
            }
        }

        Some(result)
    }
    fn size_hint(&self) -> (usize, Option<usize>) {
        (0, None) // ie no hints
    }
}