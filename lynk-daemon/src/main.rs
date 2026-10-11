use mdns_sd::{DaemonEvent, ServiceDaemon, ServiceInfo};
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
use tokio::net::{TcpListener, TcpStream};

const PROTOCOL_VERSION: &str = "1";
const SERVICE_TYPE: &str = "_lynk._tcp.local.";
const SERVICE_NAME: &str = "Lynk";
const TCP_PORT: u16 = 5000;

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    // 1. Start the TCP server.
    let address = std::env::var("LYNK_BIND").unwrap_or_else(|_| "0.0.0.0:5000".to_string());

    let listener = TcpListener::bind(&address).await?;

    println!("==================================");
    println!("           LYNK DAEMON");
    println!("==================================");
    println!("TCP listener: {address}");

    // 2. Start the mDNS daemon.
    let mdns = ServiceDaemon::new()?;

    // Listen for events reported by the mDNS worker.
    let events = mdns.monitor()?;

    std::thread::spawn(move || {
        while let Ok(event) = events.recv() {
            match event {
                DaemonEvent::Error(error) => {
                    eprintln!("[mDNS error] {error}");
                }
                DaemonEvent::Announce(name, intf) => {
                    println!("[mDNS] Announced {name} on {intf}");
                }
                _ => {} // Ignore Respond and other events.
            }
        }
    });

    // 3. Describe the service we want to advertise.
    //
    //    Passing an empty string for the IP address means "no hardcoded
    //    addresses".  Calling `enable_addr_auto()` tells the library to
    //    discover and advertise the addresses of every active interface
    //    automatically and to keep them updated if interfaces change.
    let properties = [("version", PROTOCOL_VERSION), ("protocol", "lynk-tcp")];

    let service = ServiceInfo::new(
        SERVICE_TYPE,
        SERVICE_NAME,
        "lynk-host.local.",
        "",
        TCP_PORT,
        &properties[..],
    )?
    .enable_addr_auto();

    // 4. Publish it on the local network.
    mdns.register(service)?;

    println!("mDNS service: {SERVICE_TYPE}");
    println!("Service name: {SERVICE_NAME}");
    println!("TCP port:     {TCP_PORT}");
    println!("Waiting for connections…");
    println!("Press Ctrl+C to stop.");

    // 5. Accept connections until the user stops the daemon.
    loop {
        tokio::select! {
            result = listener.accept() => {
                let (stream, peer) = result?;
                println!("[+] Connection from {peer}");

                tokio::spawn(async move {
                    if let Err(error) = handle_client(stream).await {
                        eprintln!("[Client error] {error}");
                    }
                    println!("[-] Client disconnected");
                });
            }

            result = tokio::signal::ctrl_c() => {
                result?;
                println!("\nStopping Lynk…");
                break;
            }
        }
    }

    // 6. Stop advertising the service.
    mdns.shutdown()?;
    println!("Lynk stopped.");

    Ok(())
}

async fn handle_client(stream: TcpStream) -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
    let (reader, mut writer) = stream.into_split();
    let mut reader = BufReader::new(reader);
    let mut line = String::new();

    loop {
        line.clear();

        let bytes_read = reader.read_line(&mut line).await?;

        if bytes_read == 0 {
            break;
        }

        if bytes_read > 4096 {
            writer.write_all(b"ERROR line_too_long\n").await?;
            break;
        }

        let command = line.trim();

        if command.is_empty() {
            continue;
        }

        println!("[>] Received: {command}");

        let response = match command {
            "HELLO_LYNK" => {
                format!("LYNK_HELLO {PROTOCOL_VERSION}\n")
            }
            "PING" => "PONG\n".to_string(),
            "GET_INFO" => {
                format!(
                    "LYNK_INFO version={PROTOCOL_VERSION} \
                     pairing=disabled encryption=disabled\n"
                )
            }
            "QUIT" => {
                writer.write_all(b"BYE\n").await?;
                break;
            }
            _ => "ERROR unknown_command\n".to_string(),
        };

        writer.write_all(response.as_bytes()).await?;
    }

    Ok(())
}
