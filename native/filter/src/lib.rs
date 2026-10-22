use adblock::{Engine, lists::{FilterSet, ParseOptions, RuleTypes}, request::Request};
use jni::{JNIEnv, objects::{JClass, JString, JByteArray}, sys::{jlong,jstring,jbyteArray}};
use std::{collections::HashMap, sync::{Mutex,OnceLock}, sync::atomic::{AtomicI64,Ordering}};
use std::io::{Read,Seek,SeekFrom};
static ENGINES: OnceLock<Mutex<HashMap<i64,Engine>>> = OnceLock::new();
static NEXT: AtomicI64 = AtomicI64::new(1);
fn engines() -> &'static Mutex<HashMap<i64,Engine>> { ENGINES.get_or_init(||Mutex::new(HashMap::new())) }
fn insert(engine: Engine) -> i64 { let id=NEXT.fetch_add(1,Ordering::Relaxed);engines().lock().unwrap().insert(id,engine);id }
fn normalize(input: &str) -> String {
    input.lines().filter_map(|line| {
        let l=line.trim();
        if l.is_empty() || l.starts_with('#') || l.starts_with('!') || l.starts_with('[') {return None}
        let words:Vec<&str>=l.split_whitespace().collect();
        let host=if words.len()>1 && (words[0]=="0.0.0.0" || words[0]=="127.0.0.1" || words[0]=="::") {Some(words[1])} else if words.len()==1 && !l.contains(|c:char| "|^$/*@#=".contains(c)) && l.contains('.') {Some(l)} else {None};
        Some(match host { Some(h) if h!="localhost" && h!="localhost.localdomain"=>format!("||{}^",h.trim_end_matches('.').to_lowercase()), Some(_)=>return None, None=>l.to_owned() })
    }).collect::<Vec<_>>().join("\n")
}
pub fn compile_rules(input: &str) -> Engine {
    let mut set=FilterSet::new(true);
    set.add_filter_list(normalize(input),ParseOptions {rule_types:RuleTypes::NetworkOnly,..ParseOptions::default()});
    Engine::new_with_filter_set(set)
}
#[unsafe(no_mangle)] pub extern "system" fn Java_app_antisocial_core_NativeBridge_create(mut env: JNIEnv,_:JClass,rules:JString)->jlong {
    let input:String=match env.get_string(&rules) {Ok(x)=>x.into(),Err(_)=>return 0};
    insert(compile_rules(&input))
}
#[unsafe(no_mangle)] pub extern "system" fn Java_app_antisocial_core_NativeBridge_check(mut env:JNIEnv,_:JClass,handle:jlong,url:JString,source:JString,kind:JString)->jstring {
    let url:String=env.get_string(&url).unwrap().into();let source:String=env.get_string(&source).unwrap().into();let kind:String=env.get_string(&kind).unwrap().into();
    let data=match Request::new(&url,&source,&kind,"") {
        Ok(req)=>{ let map=engines().lock().unwrap();match map.get(&handle) {Some(engine)=>{let r=engine.check_network_request(&req);serde_json::json!({"blocked":r.should_block(),"reason":r.filter.map(|f|f.to_string()).unwrap_or_default(),"rewrittenUrl":r.rewritten_url})},None=>serde_json::json!({"blocked":true,"reason":"Filter engine unavailable"})} },
        Err(_)=>serde_json::json!({"blocked":true,"reason":"Invalid request"})
    };
    env.new_string(data.to_string()).unwrap().into_raw()
}
#[unsafe(no_mangle)] pub extern "system" fn Java_app_antisocial_core_NativeBridge_release(_:JNIEnv,_:JClass,handle:jlong) {engines().lock().unwrap().remove(&handle);}
#[unsafe(no_mangle)] pub extern "system" fn Java_app_antisocial_core_NativeBridge_compiled(env:JNIEnv,_:JClass,handle:jlong)->jbyteArray {
    let map=engines().lock().unwrap();let bytes=map.get(&handle).map(|e|e.serialize()).unwrap_or_default();env.byte_array_from_slice(&bytes).unwrap().into_raw()
}
#[unsafe(no_mangle)] pub extern "system" fn Java_app_antisocial_core_NativeBridge_restore(env:JNIEnv,_:JClass,bytes:JByteArray)->jlong {
    let input=match env.convert_byte_array(bytes) {Ok(b)=>b,Err(_)=>return 0};let mut engine=Engine::new_with_list_text("");if engine.deserialize(&input).is_err(){return 0}insert(engine)
}
#[unsafe(no_mangle)] pub extern "system" fn Java_app_antisocial_core_NativeBridge_restoreFile(mut env:JNIEnv,_:JClass,path:JString,offset:jlong,length:jlong)->jlong {
    let path:String=match env.get_string(&path){Ok(p)=>p.into(),Err(_)=>return 0};
    let input=(||->std::io::Result<Vec<u8>> {
        let mut file=std::fs::File::open(path)?;
        let size=file.metadata()?.len();
        let start=u64::try_from(offset).map_err(|_|std::io::ErrorKind::InvalidInput)?;
        let count=if length == -1 {size.saturating_sub(start)}else{u64::try_from(length).map_err(|_|std::io::ErrorKind::InvalidInput)?};
        if count==0||count>512*1024*1024||start.checked_add(count).is_none_or(|end|end>size){return Err(std::io::ErrorKind::InvalidInput.into())}
        file.seek(SeekFrom::Start(start))?;
        let mut bytes=vec![0;count as usize];file.read_exact(&mut bytes)?;Ok(bytes)
    })();
    let input=match input{Ok(bytes)=>bytes,Err(_)=>return 0};
    let mut engine=Engine::new_with_list_text("");if engine.deserialize(&input).is_err(){return 0}insert(engine)
}
