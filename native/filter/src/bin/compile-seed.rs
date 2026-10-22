use std::{env,fs};

fn main() -> Result<(),Box<dyn std::error::Error>> {
    let args:Vec<_>=env::args().collect();
    if args.len()!=3 {return Err("usage: compile-seed INPUT_TEXT OUTPUT_BINARY".into())}
    if args[1]=="--inspect" {
        let mut engine=adblock::Engine::new_with_list_text("");
        engine.deserialize(&fs::read(&args[2])?)?;
        let info=engine.get_debug_info();
        println!("{}",serde_json::json!({
            "networkRules":info.source_info.iter().map(|s|s.network_filter_count as u64).sum::<u64>(),
            "cosmeticRules":info.source_info.iter().map(|s|s.cosmetic_filter_count as u64).sum::<u64>(),
            "parseErrors":info.source_info.iter().map(|s|s.parse_error as u64).sum::<u64>(),
            "compiledBytes":info.flatbuffer_size
        }));
        return Ok(())
    }
    let engine=antisocial_filter::compile_rules(&fs::read_to_string(&args[1])?);
    fs::write(&args[2],engine.serialize())?;
    Ok(())
}
