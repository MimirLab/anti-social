//! Reader for the unchanged published SSE uniform four-bit rows, NOT GGUF/Q4_K_M.
//! Source contract: RikkaBotan SSE_quantize.py at 48de1fc899574177a7c2d0d4ae90bb902190935c.
//! Native implementation GPL-3.0-or-later; model/reference notices are separate.
use jni::{JNIEnv, objects::{JClass, JString}, sys::{jfloatArray, jintArray, jlong}};
use memmap2::Mmap;
use serde_json::Value;
use sha2::{Digest, Sha256};
use std::{collections::HashMap, fs::File, io::Read, path::Path, sync::{Arc, Mutex, OnceLock, atomic::{AtomicBool, AtomicI64, Ordering}}};
use tokenizers::Tokenizer;

pub const ROWS: usize = 30_522;
pub const DIMS: usize = 512;
pub const PACKED_BYTES: usize = ROWS * DIMS / 2;
pub const TABLE_BYTES: usize = PACKED_BYTES + ROWS * 4;
pub const MAX_INPUT_BYTES: usize = 1_048_576;
pub const FILES: [(&str, u64, &str); 5] = [
    ("embedding.q4_k_m.bin", 7_935_720, "af7f5fa63246dbf4b73aef4d6d28b8e3cae82f7476edaf7417f0219061158447"),
    ("model_rest.safetensors", 6_360, "67f1caf874ce4621b8ed58e74b154623d83b6642a3bb958fcfaa83967182034b"),
    ("tokenizer.json", 711_396, "d241a60d5e8f04cc1b2b3e9ef7a4921b27bf526d9f6050ab90f9267a1f9e5c66"),
    ("config_sentence_transformers.json", 296, "a2c4f9036851e1800f7586652019ebb3ee614c1e970f887d7b2bdc26fa60bdad"),
    ("modules.json", 97, "ae26f0648b6560f6b91a7445962504d88bfd090ffc3b822ce9dfaac4bbf44c4f"),
];

#[derive(Debug)]
pub struct Encoded { pub values: [f32; DIMS], pub token_count: usize }
pub struct SseModel {
    // Read-only mmap: only the accessed packed rows are reconstructed into one accumulator.
    table: Mmap,
    tokenizer: Tokenizer,
    head: [[f32; DIMS]; 3],
    cancelled: AtomicBool,
    busy: AtomicBool,
}

fn error(message: impl Into<String>) -> String { message.into() }
fn verify_file(path: &Path, expected_size: u64, expected_hash: &str) -> Result<(), String> {
    let mut file = File::open(path).map_err(|_| error("A local embedding file is missing"))?;
    if file.metadata().map_err(|e|e.to_string())?.len() != expected_size { return Err(error("Local embedding file size mismatch")); }
    let mut digest = Sha256::new(); let mut buffer = [0u8; 65_536];
    loop { let size = file.read(&mut buffer).map_err(|e|e.to_string())?; if size == 0 { break; } digest.update(&buffer[..size]); }
    if format!("{:x}",digest.finalize()) != expected_hash { return Err(error("Local embedding integrity check failed")); }
    Ok(())
}

/// Parse only the three named learned F32 vectors, validating bounds before reading bytes.
pub fn parse_head(bytes: &[u8]) -> Result<[[f32; DIMS]; 3], String> {
    if bytes.len() < 8 { return Err(error("Truncated embedding head")); }
    let header_size = usize::try_from(u64::from_le_bytes(bytes[..8].try_into().unwrap())).map_err(|_|error("Invalid embedding header length"))?;
    if header_size > 1_048_576 { return Err(error("Embedding header too large")); }
    let data_start = 8usize.checked_add(header_size).filter(|n|*n <= bytes.len()).ok_or_else(||error("Truncated embedding header"))?;
    let header: Value = serde_json::from_slice(&bytes[8..data_start]).map_err(|_|error("Invalid embedding tensor header"))?;
    let object = header.as_object().ok_or_else(||error("Invalid embedding tensor schema"))?;
    if object.keys().any(|k| !["dyt.alpha","dyt.beta","dyt.bias","__metadata__"].contains(&k.as_str())) { return Err(error("Unexpected embedding tensor")); }
    let mut result = [[0.0f32; DIMS];3]; let mut ranges = Vec::new();
    for (index,key) in ["dyt.alpha","dyt.beta","dyt.bias"].iter().enumerate() {
        let tensor = &header[*key];
        if tensor["dtype"] != "F32" || tensor["shape"] != serde_json::json!([DIMS]) { return Err(error("Embedding tensor must be a 512-element F32 vector")); }
        let offsets = tensor["data_offsets"].as_array().filter(|a|a.len()==2).ok_or_else(||error("Invalid embedding tensor offsets"))?;
        let begin = offsets[0].as_u64().and_then(|n|usize::try_from(n).ok()).ok_or_else(||error("Invalid embedding tensor offset"))?;
        let end = offsets[1].as_u64().and_then(|n|usize::try_from(n).ok()).ok_or_else(||error("Invalid embedding tensor offset"))?;
        if end.checked_sub(begin) != Some(DIMS*4) || data_start.checked_add(end).is_none_or(|n|n>bytes.len()) || ranges.iter().any(|(s,e)|begin<*e && end>*s) { return Err(error("Embedding tensor outside file or overlapping")); }
        ranges.push((begin,end));
        for (dim,chunk) in bytes[data_start+begin..data_start+end].chunks_exact(4).enumerate() {
            let value = f32::from_le_bytes(chunk.try_into().unwrap());
            if !value.is_finite() { return Err(error("Embedding head contains a non-finite coefficient")); }
            result[index][dim] = value;
        }
    }
    Ok(result)
}

#[inline] pub fn decode_nibble(value: u8, scale: f32) -> f32 { (f32::from(value) / 7.5 - 1.0) * scale }

impl SseModel {
    pub fn open(directory: &Path) -> Result<Self,String> {
        // One app-owned CPU lane: tokenizer work must not create an independent pool.
        tokenizers::utils::parallelism::set_parallelism(false);
        for (name,size,hash) in FILES { verify_file(&directory.join(name),size,hash)?; }
        let file = File::open(directory.join(FILES[0].0)).map_err(|e|e.to_string())?;
        // App-private, hash-verified immutable pack. Installer must replace directories atomically.
        let table = unsafe { Mmap::map(&file) }.map_err(|e|e.to_string())?;
        let head = parse_head(&std::fs::read(directory.join(FILES[1].0)).map_err(|e|e.to_string())?)?;
        let mut tokenizer = Tokenizer::from_file(directory.join(FILES[2].0)).map_err(|e|format!("Cannot open SSE tokenizer: {e}"))?;
        tokenizer.with_padding(None);
        tokenizer.with_truncation(None).map_err(|e|e.to_string())?;
        if tokenizer.get_vocab_size(true) != ROWS { return Err(error("Wrong embedding vocabulary")); }
        // Validate each unaligned little-endian row scale once, without decoding the matrix.
        for row in 0..ROWS {
            let offset = PACKED_BYTES+row*4;
            let scale = f32::from_le_bytes(table[offset..offset+4].try_into().unwrap());
            if !scale.is_finite() || scale < 0.0 { return Err(error("Invalid embedding row scale")); }
        }
        Ok(Self {table,tokenizer,head,cancelled:AtomicBool::new(false),busy:AtomicBool::new(false)})
    }
    pub fn tokenize(&self, text: &str) -> Result<Vec<u32>,String> {
        if text.len() > MAX_INPUT_BYTES { return Err(error("Embedding input exceeds 1 MiB; split it into retained items")); }
        let encoding = self.tokenizer.encode(text,false).map_err(|e|format!("Cannot tokenize embedding input: {e}"))?;
        Ok(encoding.get_ids().to_vec())
    }
    pub fn cancel(&self) { self.cancelled.store(true,Ordering::Release); }
    pub fn encode(&self,text:&str) -> Result<Option<Encoded>,String> {
        if self.busy.compare_exchange(false,true,Ordering::AcqRel,Ordering::Acquire).is_err() { return Err(error("Embedding worker is busy")); }
        struct BusyGuard<'a>(&'a AtomicBool); impl Drop for BusyGuard<'_> { fn drop(&mut self){self.0.store(false,Ordering::Release);} }
        let _guard=BusyGuard(&self.busy); self.cancelled.store(false,Ordering::Release);
        let ids=self.tokenize(text)?;
        if self.cancelled.load(Ordering::Acquire) {return Err(error("Embedding cancelled"));}
        if ids.is_empty() {return Ok(None);}
        let mut values=[0.0f32;DIMS];
        for (occurrence, id) in ids.iter().enumerate() {
            if occurrence%32==0 && self.cancelled.load(Ordering::Acquire) {return Err(error("Embedding cancelled"));}
            let row=*id as usize;if row>=ROWS{return Err(error("Tokenizer ID outside embedding vocabulary"));}
            let offset=PACKED_BYTES+row*4; let scale=f32::from_le_bytes(self.table[offset..offset+4].try_into().unwrap());
            for (pair,packed) in self.table[row*DIMS/2..(row+1)*DIMS/2].iter().enumerate() {
                values[pair*2]+=decode_nibble(packed>>4,scale);
                values[pair*2+1]+=decode_nibble(packed&15,scale);
            }
        }
        let count=ids.len() as f32;let mut squared_norm=0.0f32;
        for (dim,value) in values.iter_mut().enumerate() {
            let mean=*value/count;
            *value=self.head[1][dim]*(self.head[0][dim]*mean+self.head[2][dim]).tanh();
            squared_norm+=*value**value;
        }
        let norm=squared_norm.sqrt();if !norm.is_finite()||norm<=0.0{return Err(error("Invalid zero or non-finite embedding"));}
        for value in &mut values {*value/=norm;}
        if self.cancelled.load(Ordering::Acquire){return Err(error("Embedding cancelled"));}
        Ok(Some(Encoded{values,token_count:ids.len()}))
    }
}

static MODELS:OnceLock<Mutex<HashMap<i64,Arc<SseModel>>>>=OnceLock::new();
static NEXT:AtomicI64=AtomicI64::new(1);
fn models()->&'static Mutex<HashMap<i64,Arc<SseModel>>>{MODELS.get_or_init(||Mutex::new(HashMap::new()))}
fn model(handle:jlong)->Result<Arc<SseModel>,String>{models().lock().map_err(|_|error("Embedding handle store unavailable"))?.get(&handle).cloned().ok_or_else(||error("Embedding model closed"))}
fn fail(env:&mut JNIEnv,message:String){let class=if message=="Embedding cancelled"{"java/lang/InterruptedException"}else{"java/lang/IllegalStateException"};let _=env.throw_new(class,message);}
#[unsafe(no_mangle)]pub extern "system" fn Java_app_antisocial_core_SseNativeBridge_open(mut env:JNIEnv,_:JClass,path:JString)->jlong{
    let path:String=match env.get_string(&path){Ok(s)=>s.into(),Err(_)=>return 0};
    match SseModel::open(Path::new(&path)){Ok(m)=>{let id=NEXT.fetch_add(1,Ordering::Relaxed);models().lock().unwrap().insert(id,Arc::new(m));id},Err(e)=>{fail(&mut env,e);0}}
}
#[unsafe(no_mangle)]pub extern "system" fn Java_app_antisocial_core_SseNativeBridge_encode(mut env:JNIEnv,_:JClass,handle:jlong,text:JString)->jfloatArray{
    let result=(||{let text:String=env.get_string(&text).map_err(|e|e.to_string())?.into();model(handle)?.encode(&text)})();
    match result{Ok(encoded)=>{let values=encoded.map(|e|{let mut out=Vec::with_capacity(DIMS+1);out.push(e.token_count as f32);out.extend(e.values);out}).unwrap_or_default();match env.new_float_array(values.len()as i32){Ok(array)=>{if env.set_float_array_region(&array,0,&values).is_ok(){array.into_raw()}else{std::ptr::null_mut()}},Err(_)=>std::ptr::null_mut()}},Err(e)=>{fail(&mut env,e);std::ptr::null_mut()}}
}
#[unsafe(no_mangle)]pub extern "system" fn Java_app_antisocial_core_SseNativeBridge_tokenize(mut env:JNIEnv,_:JClass,handle:jlong,text:JString)->jintArray{
    let result=(||{let text:String=env.get_string(&text).map_err(|e|e.to_string())?.into();model(handle)?.tokenize(&text)})();
    match result{Ok(ids)=>{let values:Vec<i32>=ids.into_iter().map(|n|n as i32).collect();match env.new_int_array(values.len()as i32){Ok(array)=>{if env.set_int_array_region(&array,0,&values).is_ok(){array.into_raw()}else{std::ptr::null_mut()}},Err(_)=>std::ptr::null_mut()}},Err(e)=>{fail(&mut env,e);std::ptr::null_mut()}}
}
#[unsafe(no_mangle)]pub extern "system" fn Java_app_antisocial_core_SseNativeBridge_cancel(_:JNIEnv,_:JClass,handle:jlong){if let Ok(m)=model(handle){m.cancel();}}
#[unsafe(no_mangle)]pub extern "system" fn Java_app_antisocial_core_SseNativeBridge_close(_:JNIEnv,_:JClass,handle:jlong){if let Ok(mut map)=models().lock(){if let Some(m)=map.remove(&handle){m.cancel();}}}

#[cfg(test)]mod tests{
    use super::*;
    #[test]fn nibble_order_and_row_scaling(){assert_eq!(decode_nibble(0,3.0),-3.0);assert_eq!(decode_nibble(15,3.0),3.0);assert!((decode_nibble(8,3.0)-0.2).abs()<0.000001);let byte=0x3e;assert!(decode_nibble(byte>>4,2.0)<0.0);assert!(decode_nibble(byte&15,2.0)>0.0);}
    #[test]fn malformed_heads_fail_before_access(){assert!(parse_head(&[]).is_err());assert!(parse_head(&u64::MAX.to_le_bytes()).is_err());let mut b=9u64.to_le_bytes().to_vec();b.extend(b"{invalid}");assert!(parse_head(&b).is_err());}
    #[test]fn tensor_schema_and_overlaps_fail(){for change in ["dtype","shape","data_offsets"]{let mut h=serde_json::json!({"dyt.alpha":{"dtype":"F32","shape":[512],"data_offsets":[0,2048]},"dyt.beta":{"dtype":"F32","shape":[512],"data_offsets":[2048,4096]},"dyt.bias":{"dtype":"F32","shape":[512],"data_offsets":[4096,6144]}});h["dyt.alpha"][change]=match change{"dtype"=>serde_json::json!("F16"),"shape"=>serde_json::json!([256]),_=>serde_json::json!([2048,4096])};let s=serde_json::to_vec(&h).unwrap();let mut b=(s.len()as u64).to_le_bytes().to_vec();b.extend(s);b.extend(vec![0;6144]);assert!(parse_head(&b).is_err(),"{change}");}}
    #[test]fn pinned_pack_parity(){
        let Ok(directory)=std::env::var("ANTISOCIAL_SSE_TEST_PACK")else{return};
        let m=SseModel::open(Path::new(&directory)).unwrap();
        let corpus:Value=serde_json::from_slice(&std::fs::read(std::env::var("ANTISOCIAL_SSE_REFERENCE").expect("reference corpus path")).unwrap()).unwrap();
        let mut maximum_error=0.0f32;let mut minimum_cosine=1.0f32;let mut maximum_head_error=0.0f32;let mut maximum_mean_error=0.0f32;
        for row in corpus["rows"].as_array().unwrap(){
            let id=row["id"].as_u64().unwrap()as usize;let scale_offset=PACKED_BYTES+id*4;
            let scale=f32::from_le_bytes(m.table[scale_offset..scale_offset+4].try_into().unwrap());
            for dim in 0..DIMS{let packed=m.table[id*DIMS/2+dim/2];let q=if dim%2==0{packed>>4}else{packed&15};let actual=decode_nibble(q,scale);let expected=row["values"][dim].as_f64().unwrap()as f32;assert_eq!(actual,expected,"row {id},dim {dim}");}
        }
        let mut timings=Vec::new();
        for (case,row) in corpus["cases"].as_array().unwrap().iter().enumerate(){
            let text=row["text"].as_str().unwrap();let expected_ids:Vec<u32>=row["ids"].as_array().unwrap().iter().map(|x|x.as_u64().unwrap()as u32).collect();
            assert_eq!(m.tokenize(text).unwrap(),expected_ids,"tokenizer case {case}");
            let start=std::time::Instant::now();let output=m.encode(text).unwrap();timings.push(start.elapsed().as_secs_f64()*1000.0);
            if expected_ids.is_empty(){assert!(output.is_none());continue;}
            // Intermediate parity against published EmbeddingBag and learned DyT head.
            let mut mean=[0.0f32;DIMS];
            for id in &expected_ids{let id=*id as usize;let offset=PACKED_BYTES+id*4;let scale=f32::from_le_bytes(m.table[offset..offset+4].try_into().unwrap());for dim in 0..DIMS{let p=m.table[id*DIMS/2+dim/2];mean[dim]+=decode_nibble(if dim%2==0{p>>4}else{p&15},scale);}}
            for dim in 0..DIMS{mean[dim]/=expected_ids.len()as f32;let head=m.head[1][dim]*(m.head[0][dim]*mean[dim]+m.head[2][dim]).tanh();let mean_error=(mean[dim]-row["mean"][dim].as_f64().unwrap()as f32).abs();let head_error=(head-row["head"][dim].as_f64().unwrap()as f32).abs();maximum_mean_error=maximum_mean_error.max(mean_error);maximum_head_error=maximum_head_error.max(head_error);assert!(mean_error<0.000004,"mean case {case},dim {dim}: {mean_error}");assert!(head_error<0.000004,"head case {case},dim {dim}: {head_error}");}
            let output=output.unwrap();let reference:Vec<f32>=row["vector"].as_array().unwrap().iter().map(|v|v.as_f64().unwrap()as f32).collect();
            let max_error=output.values.iter().zip(&reference).map(|(a,b)|(a-b).abs()).fold(0.0,f32::max);let cosine:f32=output.values.iter().zip(&reference).map(|(a,b)|a*b).sum();
            maximum_error=maximum_error.max(max_error);minimum_cosine=minimum_cosine.min(cosine);
            assert!(max_error<0.000004,"max_error={max_error} for case {case}");assert!(cosine>0.99999,"cosine={cosine} for case {case}");
        }
        let report=serde_json::json!({"schema":"antisocial-sse-native-parity-v1","cases":corpus["cases"].as_array().unwrap().len(),"rows":corpus["rows"].as_array().unwrap().len(),"max_coordinate_error":maximum_error,"minimum_cosine":minimum_cosine,"max_mean_error":maximum_mean_error,"max_head_error":maximum_head_error,"case_ms":timings,"mapped_table_bytes":m.table.len(),"matrix_expanded":false});
        if let Ok(path)=std::env::var("ANTISOCIAL_SSE_PARITY_REPORT"){std::fs::write(path,serde_json::to_vec_pretty(&report).unwrap()).unwrap();}
        println!("{report}");
    }
}
