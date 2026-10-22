# Electra Smart Binding

This binding controls Electra air conditioners that are registered in the Electra Smart mobile app.
It talks to the same cloud service as the app, so no local network access or additional hardware is required.

The implementation follows the Home Assistant [Electra Smart integration](https://www.home-assistant.io/integrations/electrasmart/):
the account is polled every 30 seconds, commands are sent as a complete device state, and units that stop reporting to
the cloud are marked offline.

This is an unofficial community binding. It is not affiliated with, endorsed by, or supported by Electra, and it uses
an undocumented cloud API that can change without notice.

## Supported Things

| Thing type | Description |
| ---------- | ----------- |
| `account`  | Bridge representing the Electra Smart account. |
| `ac`       | An Electra air conditioner. |

## Discovery

1. Create an `account` bridge and enter the phone number used by the Electra Smart app, in local format (for example `0521234567`).
1. The binding requests a one-time password by SMS and the bridge goes offline with a message telling you what to do.
1. Enter the code in the bridge configuration (or run the `submitOtp` thing action) and save.
1. Once the bridge is online, run a scan to discover the air conditioners, or add them manually.

Discovered air conditioners are created as things below the account bridge. For a fully file-based setup, see
[File-based Configuration](#file-based-configuration).

## Thing Configuration

### Account Bridge

| Name            | Type    | Default | Description |
| --------------- | ------- | ------- | ----------- |
| `phoneNumber`   | text    |         | Phone number of the Electra Smart app account in local format, for example `0521234567`. Required. A `+972` or `972` prefix is normalized automatically. |
| `otp`           | text    |         | One-time password received by SMS. Cleared after a successful sign-in when the thing is managed through the UI. |
| `refreshInterval` | integer | 30    | Poll interval in seconds. Minimum 30. |

The cloud identifies the account by the phone number it receives, so use the same local format as the Electra Smart app.
An international prefix can resolve to a separate, empty account, in which case the bridge reports that the cloud
returned no device list. The binding strips a `+972` or `972` prefix automatically before signing in.

The long-lived token and the generated IMEI are stored in openHAB's storage service, not in the thing configuration.
Removing the bridge deletes the stored session.

### Air Conditioner

| Name       | Type | Description |
| ---------- | ---- | ----------- |
| `deviceId` | text | Identifier of the air conditioner. Filled in automatically by discovery. |

### File-based Configuration

Things can also be created in `.things` files instead of the UI. Put them in `$OPENHAB_CONF/things/` (`/etc/openhab/things`
on package installations, `$OPENHAB_HOME/conf/things` for manual installs and `/openhab/conf/things` in the Docker image).
openHAB watches existing files and reloads them automatically; a newly added file may require one restart, as described
below. Define the account bridge and nest the air conditioners inside it so they use the account as their bridge:

```java
Bridge electrasmart:account:home "Electra Smart Account" [ phoneNumber="0501234567", refreshInterval=30 ] {
    Thing ac livingroom "Living Room A/C" [ deviceId="1234123456" ]
}
```

The resulting channel UIDs are `electrasmart:ac:home:livingroom:<channel>`, as used in the [full example](#full-example).

If the account bridge already exists in the UI, keep it there and define only the air conditioners in the file. Use the
bridge's thing ID in both the thing UID and the bridge reference. For a bridge UID of
`electrasmart:account:myaccount`:

```java
Thing electrasmart:ac:myaccount:livingroom "Living Room A/C" (electrasmart:account:myaccount) [ deviceId="1234123456" ]
```

The resulting channel UIDs are `electrasmart:ac:myaccount:livingroom:<channel>`. Do not define the same unit both in the
UI and in the file.

If a newly created `.things` file does not produce things while openHAB is running, restart openHAB once. The file-based
thing provider waits for the binding's thing types to be registered, which is guaranteed at startup. Edits to an
already loaded file are picked up automatically.

The device id of each unit is not shown in the Electra app. Start with the bridge only, sign in, and run a discovery scan
once to read the ids from the inbox:

```shell
curl -X POST http://localhost:8080/rest/discovery/bindings/electrasmart/scan
curl -s http://localhost:8080/rest/inbox | jq -r '.[] | "\(.label): \(.properties.deviceId)"'
```

Add the printed ids to the `Thing ac` entries. The inbox results can be ignored afterwards; the things in the file are
independent of them.

The bridge requests a one-time password by SMS automatically when it has no stored session. Either add the code to the
bridge temporarily:

```java
Bridge electrasmart:account:home "Electra Smart Account" [
    phoneNumber="0501234567",
    otp="123456"
]
```

or keep it out of the file with a String item and a rule:

```java
String ElectraOtp "Electra One-Time Password"

rule "Submit Electra OTP"
when
    Item ElectraOtp received command
then
    val actions = getActions("electrasmart", "electrasmart:account:home")
    if (actions !== null) {
        actions.submitOtp(receivedCommand.toString)
    }
end
```

Send the code to the item, for example over the REST API:

```shell
curl -X POST -H "Content-Type: text/plain" -d "123456" http://localhost:8080/rest/items/ElectraOtp
```

The binding cannot modify `.things` files, so it cannot remove an `otp` line from them. Leaving the code in place is safe:
once it has expired, the binding falls back to the stored session and logs a warning. The stored token, IMEI and throttle
timestamps are kept in openHAB's storage, not in the files. That storage is keyed by the bridge UID, so renaming the
bridge requires a new sign-in.

## Channels

| Channel               | Type                 | Read/write | Description |
| --------------------- | -------------------- | ---------- | ----------- |
| `power`               | Switch               | R/W        | Turns the unit on or off. Turning on restores the mode the unit ran in before. |
| `operational-mode`    | String               | R/W        | `AUTO`, `COOL`, `DRY`, `FAN` or `HEAT`. |
| `target-temperature`  | Number:Temperature   | R/W        | Target temperature, 17 °C to 30 °C in 1 °C steps. |
| `indoor-temperature`  | Number:Temperature   | R          | Room temperature reported by the unit. |
| `fan-speed`           | String               | R/W        | `AUTO`, `LOW`, `MED` or `HIGH`. |
| `swing-mode`          | String               | R/W        | `OFF`, `VERTICAL`, `HORIZONTAL` or `BOTH`. Ignored when the unit does not report that swing direction. |
| `shabat-mode`         | Switch               | R/W        | Shabat (Sabbath) mode. |
| `turbo-mode`          | Switch               | R/W        | Turbo mode (advanced, only when the unit reports it). |
| `sleep-function`      | Switch               | R/W        | Sleep function (advanced, only when the unit reports it). |
| `ifeel`               | Switch               | R/W        | iFeel mode (advanced, only when the unit reports it). |
| `last-contact`        | Number:Time          | R          | Seconds since the unit last reported to the cloud (advanced). |

Temperatures are shown in the measurement system configured in openHAB and converted to Celsius before they are sent
to the cloud.

A unit that has not reported to the cloud for 120 seconds is marked offline. The account bridge also backs off after
four consecutive polling failures.

## Thing Actions

The account bridge provides these actions:

| Action | Description |
| ------ | ----------- |
| `requestOtp` | Requests a new one-time password. Useful after an intruder lockout or when the code expired. |
| `submitOtp(otp)` | Signs in with a one-time password without editing the bridge configuration. |
| `signOut` | Drops the stored token and starts the sign-in flow again. |

## Full Example

### `electrasmart.things`

```java
Bridge electrasmart:account:home "Electra Smart Account" [ phoneNumber="0501234567", refreshInterval=30 ] {
    Thing ac livingroom "Living Room A/C" [ deviceId="1234123456" ]
    Thing ac bedroom "Bedroom A/C" [ deviceId="1234123457" ]
}
```

### `electrasmart.items`

```java
Switch              LivingRoom_Power       "Living Room Power"                { channel="electrasmart:ac:home:livingroom:power" }
String              LivingRoom_Mode        "Living Room Mode [%s]"            { channel="electrasmart:ac:home:livingroom:operational-mode" }
Number:Temperature  LivingRoom_TargetTemp  "Living Room Target [%.0f %unit%]" { channel="electrasmart:ac:home:livingroom:target-temperature" }
Number:Temperature  LivingRoom_IndoorTemp  "Living Room Indoor [%.0f %unit%]" { channel="electrasmart:ac:home:livingroom:indoor-temperature" }
String              LivingRoom_Fan         "Living Room Fan [%s]"             { channel="electrasmart:ac:home:livingroom:fan-speed" }
String              LivingRoom_Swing       "Living Room Swing [%s]"           { channel="electrasmart:ac:home:livingroom:swing-mode" }
Switch              LivingRoom_Shabat       "Living Room Shabat"               { channel="electrasmart:ac:home:livingroom:shabat-mode" }
```

### `electrasmart.sitemap`

```perl
sitemap electrasmart label="Electra Smart" {
    Frame label="Living Room" {
        Switch item=LivingRoom_Power
        Selection item=LivingRoom_Mode mappings=[AUTO="Auto", COOL="Cool", DRY="Dry", FAN="Fan", HEAT="Heat"]
        Setpoint item=LivingRoom_TargetTemp minValue=17 maxValue=30 step=1
        Text item=LivingRoom_IndoorTemp
        Selection item=LivingRoom_Fan mappings=[AUTO="Auto", LOW="Low", MED="Medium", HIGH="High"]
        Selection item=LivingRoom_Swing mappings=[OFF="Off", VERTICAL="Vertical", HORIZONTAL="Horizontal", BOTH="Both"]
        Switch item=LivingRoom_Shabat
    }
}
```

## Building

The binding is a standard openHAB 5.2 bundle and needs JDK 21 and Maven 3.9 or newer.

```shell
./build.sh
```

`settings.xml` points Maven at the openHAB JFrog repositories, which host the openHAB core artifacts. Add
`-Dmarkdownlint.skip=true` when the npm registry is not reachable.

To build the bundle inside an `openhab-addons` clone, place the clone next to this repository (or set
`OPENHAB_ADDONS_DIR`):

```shell
./build.sh --stage-upstream
```

The script copies the bundle to `bundles/org.openhab.binding.electrasmart`, switches the parent to the reactor
SNAPSHOT, adds the module to `bundles/pom.xml` and builds only that module. It is intended for preparing an upstream
pull request.

### Releasing

The GitHub Actions workflow builds the jar on every push and pull request. To create a release, push a tag that matches
the bundle version:

```shell
git tag v5.2.0
git push origin v5.2.0
```

The workflow runs the tests, builds the jar and attaches it to a GitHub release. You can also start the workflow
manually and enable the `release` input with the same tag value.

## Troubleshooting

### The bridge reports an intruder lockout

The Electra cloud locks an account when a session id is requested too often. The binding already throttles session id
requests to one per five minutes, but a restart or another client using the same account can still trigger the lockout.

Run the `requestOtp` action (or press "Save" on the bridge after clearing the one-time password field), enter the code
received by SMS and the bridge signs in again.

### The bridge stays offline with "A one-time password was requested less than two minutes ago"

The binding refuses to send SMS messages in quick succession. Wait for the message or run `requestOtp` later.

### A unit shows as offline

The Electra cloud reports how long ago the unit last contacted it. If that is more than 120 seconds the thing is marked
offline. Check the unit's Wi-Fi connection and whether it is visible in the Electra Smart app.

### Commands are slow

Sending a command requires a valid session id. If the session expired, the binding has to fetch a new one and the cloud
throttles that to one request per five minutes. Commands sent while the binding is polling may therefore take a few
seconds.
