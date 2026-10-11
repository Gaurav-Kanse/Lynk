use mdns_sd::{RecvTimeoutError, ServiceDaemon, ServiceEvent};
use std::time::{Duration, Instant};

const SERVICE_TYPE: &str = "_lynk._tcp.local.";

/// How long to wait for the first ServiceFound event before printing
/// diagnostic hints.
const DISCOVERY_TIMEOUT: Duration = Duration::from_secs(10);

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let mdns = ServiceDaemon::new()?;
    let receiver = mdns.browse(SERVICE_TYPE)?;

    println!("Searching for Lynk services ({SERVICE_TYPE})…");
    println!("Press Ctrl+C to stop.\n");

    let start = Instant::now();
    let mut found = false;

    loop {
        // Use recv_timeout so we can print diagnostics when discovery is
        // taking too long rather than blocking forever.
        match receiver.recv_timeout(Duration::from_secs(2)) {
            Ok(event) => match event {
                ServiceEvent::SearchStarted(info) => {
                    println!("[browse] {info}");
                }

                ServiceEvent::ServiceFound(service_type, fullname) => {
                    found = true;
                    println!("[found]    type={service_type}  name={fullname}");
                    println!("           Resolving addresses…");
                }

                ServiceEvent::ServiceResolved(info) => {
                    found = true;
                    println!("[resolved] {}", info.get_fullname());
                    println!("           host = {}", info.get_hostname());
                    println!("           port = {}", info.get_port());

                    let addrs = info.get_addresses();
                    if addrs.is_empty() {
                        println!("           addresses = (none yet)");
                    } else {
                        for addr in addrs {
                            println!("           addr = {addr}");
                        }
                    }

                    let props = info.get_properties();
                    for prop in props.iter() {
                        println!("           {}={}", prop.key(), prop.val_str());
                    }
                    println!();
                }

                ServiceEvent::ServiceRemoved(service_type, fullname) => {
                    println!("[removed]  type={service_type}  name={fullname}");
                }

                ServiceEvent::SearchStopped(service_type) => {
                    println!("[stopped]  {service_type}");
                    break;
                }

                other => {
                    println!("[event]    {other:?}");
                }
            },

            Err(RecvTimeoutError::Timeout) => {
                if !found && start.elapsed() >= DISCOVERY_TIMEOUT {
                    eprintln!("WARNING: No Lynk service found after {DISCOVERY_TIMEOUT:?}.");
                    eprintln!("Possible causes:");
                    eprintln!("  1. The lynk-daemon is not running (start it first).");
                    eprintln!("  2. A firewall is blocking UDP port 5353 (mDNS multicast).");
                    eprintln!("  3. No active network interface with multicast support.");
                    eprintln!("Still listening — press Ctrl+C to stop.\n");
                    // Only print this hint once.
                    found = true;
                }
            }

            Err(RecvTimeoutError::Disconnected) => {
                eprintln!("mDNS daemon channel closed unexpectedly.");
                break;
            }
        }
    }

    mdns.shutdown()?;
    Ok(())
}
