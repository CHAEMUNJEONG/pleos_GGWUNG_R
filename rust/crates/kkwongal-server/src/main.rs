use std::env;
use std::fs;
use std::path::PathBuf;
use std::sync::Arc;
use std::thread;
use std::time::Duration;

use kkwongal_server::{system_clock_ms, LeagueHttpServer, Store};
use serde_json::json;

fn main() {
    let args: Vec<String> = env::args().skip(1).collect();
    let mut db_path = PathBuf::from("data").join("league-v2.sqlite3");
    let mut port: u16 = 8765;
    let mut ready_file: Option<PathBuf> = None;
    let mut create_user: Option<String> = None;

    let mut i = 0;
    while i < args.len() {
        match args[i].as_str() {
            "--db" => {
                i += 1;
                if i < args.len() {
                    db_path = PathBuf::from(&args[i]);
                }
            }
            "--port" => {
                i += 1;
                if i < args.len() {
                    port = args[i].parse().expect("invalid --port");
                }
            }
            "--ready-file" => {
                i += 1;
                if i < args.len() {
                    ready_file = Some(PathBuf::from(&args[i]));
                }
            }
            "--create-user" => {
                i += 1;
                if i < args.len() {
                    create_user = Some(args[i].clone());
                }
            }
            other => {
                eprintln!("Unknown argument: {other}");
                std::process::exit(2);
            }
        }
        i += 1;
    }

    let store = match Store::open(&db_path, Arc::new(system_clock_ms)) {
        Ok(s) => Arc::new(s),
        Err(e) => {
            eprintln!("Failed to open database: {e}");
            std::process::exit(1);
        }
    };

    if let Some(name) = create_user {
        match store.create_user(&name) {
            Ok(user) => {
                println!("{}", serde_json::to_string(&user).unwrap());
                return;
            }
            Err(err) => {
                eprintln!("create-user failed ({}): {}", err.status, err.code);
                std::process::exit(1);
            }
        }
    }

    let server = match LeagueHttpServer::start(store, port) {
        Ok(s) => s,
        Err(e) => {
            eprintln!("Failed to start server: {e}");
            std::process::exit(1);
        }
    };
    let url = server.url();
    if let Some(ready) = ready_file {
        if let Some(parent) = ready.parent() {
            let _ = fs::create_dir_all(parent);
        }
        let staged = ready.with_extension("tmp");
        let content = serde_json::to_string(&json!({ "url": url })).unwrap();
        fs::write(&staged, &content).expect("write ready tmp failed");
        if fs::rename(&staged, &ready).is_err() {
            fs::write(&ready, &content).expect("write ready file failed");
            let _ = fs::remove_file(&staged);
        }
    }
    println!("Local development league (Rust): {url} (Ctrl+C to stop)");
    loop {
        thread::sleep(Duration::from_secs(60));
    }
}
