#!/usr/bin/env bash
# Shared parsing for command-line and environment launch options.
td20_parse_words() {
    local destination=$1 mode=$2
    shift 2
    local -n parsed=$destination
    mapfile -d '' -t parsed < <(python3 - "$mode" "$@" <<'PY'
import shlex
import sys

try:
    mode, *values = sys.argv[1:]
    arguments = []
    if mode == 'options':
        arguments = shlex.split(values[0])
    else:
        seen = False
        for value in values:
            if value.startswith(('-Dexec.args=', '-Dtd20.args=')):
                if seen:
                    raise ValueError('Supply the Java argument property only once.')
                seen = True
                nested = shlex.split(value.split('=', 1)[1])
                if any(item.startswith(('-Dexec.args=', '-Dtd20.args=')) for item in nested):
                    raise ValueError('Nested Java argument properties are unsupported.')
                arguments.extend(nested)
            else:
                arguments.append(value)
    # The final marker lets the caller detect errors in process substitution.
    sys.stdout.buffer.write(b''.join(value.encode() + b'\0' for value in arguments))
    sys.stdout.buffer.write(b'td20-arguments-complete\0')
except ValueError as error:
    print(f'Invalid launch arguments: {error}', file=sys.stderr)
    sys.exit(2)
PY
    )
    if (( ${#parsed[@]} == 0 )) || [[ ${parsed[-1]} != td20-arguments-complete ]]; then
        return 2
    fi
    unset 'parsed[-1]'
}

td20_collect_jvm_options() {
    local -n selected_jvm=$1 selected_arguments=$2
    shift 2
    local option value
    for option in "$@"; do
        case "$option" in
            -Dexec.mainClass=*) selected_arguments+=("$option") ;;
            -D?*)
                if [[ $option == *=* ]]; then
                    selected_jvm+=("-R$option")
                else
                    selected_jvm+=("-R$option=true")
                fi ;;
            -R?*|-J?*) selected_jvm+=("$option") ;;
            --jvm-option=*)
                value=${option#*=}
                [[ $value == -?* ]] || { printf 'Invalid JVM option: %s\n' "$value" >&2; return 2; }
                selected_jvm+=("-R$value") ;;
            -D|-R|-J) printf 'Incomplete JVM option: %s\n' "$option" >&2; return 2 ;;
            *) selected_arguments+=("$option") ;;
        esac
    done
}

td20_integer_setting() {
    local -n integer_value=$1
    local value=$2 minimum=$3
    if [[ ! $value =~ ^[0-9]+$ ]]; then
        printf 'Invalid numeric setting %s: %s\n' "$1" "$value" >&2
        return 2
    fi
    value=${value#"${value%%[!0]*}"}
    value=${value:-0}
    if (( ${#value} > 19 )) || { (( ${#value} == 19 )) && [[ $value > 9223372036854775807 ]]; }; then
        printf 'Numeric setting exceeds signed 64-bit range: %s\n' "$1" >&2
        return 2
    fi
    integer_value=$((10#$value))
    if (( integer_value < minimum )); then
        printf 'Numeric setting %s must be at least %s\n' "$1" "$minimum" >&2
        return 2
    fi
}

td20_resource_options() {
    python3 - "$@" <<'PY'
import re
import sys

try:
    heap, initial, slots, budget, queue, *options = sys.argv[1:]
    heap += 'g'
    initial += 'g'
    devices = '0'
    explicit_initial = False
    for option in options:
        if option.startswith('-R-Xmx'):
            heap = option[6:]
        elif option.startswith('-R-XX:MaxHeapSize='):
            heap = option.split('=', 1)[1]
        elif option.startswith(('-R-Xms', '-R-XX:InitialHeapSize=')):
            initial = option[6:] if option.startswith('-R-Xms') else option.split('=', 1)[1]
            explicit_initial = True
        elif option.startswith('-R-Dtd20.gpuDevices='):
            devices = option.split('=', 1)[1]
        elif option.startswith('-R-Dtd20.gpuSlots='):
            slots = option.split('=', 1)[1]
        elif option.startswith('-R-Dtd20.gpuBudgetMB='):
            budget = option.split('=', 1)[1]
        elif option.startswith('-R-Dtd20.gpuQueueCapacity='):
            queue = option.split('=', 1)[1]

    def memory_bytes(value):
        match = re.fullmatch(r'([0-9]+)([kKmMgGtT]?)', value)
        if not match:
            raise ValueError(f'Invalid JVM heap size: {value}')
        shifts = {'': 0, 'k': 10, 'm': 20, 'g': 30, 't': 40}
        amount = int(match[1]) << shifts[match[2].lower()]
        if amount > 2**63 - 1:
            raise ValueError('JVM heap size exceeds signed 64-bit range')
        return amount

    def integer(value, minimum, maximum=2**31 - 1):
        if not re.fullmatch(r'[0-9]+', value) or not minimum <= int(value) <= maximum:
            raise ValueError(f'Invalid GPU resource setting: {value}')
        return int(value)

    heap_bytes = memory_bytes(heap)
    initial_bytes = memory_bytes(initial)
    if heap_bytes > 0:
        if explicit_initial and initial_bytes > heap_bytes:
            raise ValueError('Initial JVM heap exceeds maximum heap')
        initial_bytes = min(initial_bytes, heap_bytes)
    slots = integer(slots, 1)
    budget = integer(budget, 1, (2**63 - 1) // (1 << 20))
    queue = integer(queue, 0)
    tokens = [value.strip() for value in devices.split(',')]
    ordinals = [integer(value[1:] if value.startswith('+') else value, 0) for value in tokens]
    if len(set(ordinals)) != len(ordinals):
        raise ValueError('GPU device ordinals must be distinct')
    devices = ','.join(map(str, ordinals))
    display = format(heap_bytes / (1 << 30), 'g') if heap_bytes else 'JVM-determined'
    print(heap_bytes, initial_bytes, display, slots, budget, queue, devices, len(ordinals),
          len(ordinals) * slots * budget // 1024)
except ValueError as error:
    print(f'Invalid resource options: {error}', file=sys.stderr)
    sys.exit(2)
PY
}

td20_numa_required() {
    python3 - <<'PY'
import os
from pathlib import Path

cpus = os.sched_getaffinity(0)
packages = set()
nodes = set()
for cpu in cpus:
    directory = Path(f'/sys/devices/system/cpu/cpu{cpu}')
    try:
        packages.add((directory / 'topology/physical_package_id').read_text().strip())
    except OSError:
        pass
    nodes.update(path.name for path in directory.glob('node[0-9]*'))
print(int(len(packages) > 1 or len(nodes) > 1))
PY
}

td20_add_numa_option() {
    local -n numa_options=$1
    local option
    for option in "${numa_options[@]}"; do
        case "$option" in
            -R-XX:+UseNUMA|-R-XX:-UseNUMA) return ;;
        esac
    done
    if (( $(td20_numa_required) )); then numa_options+=(-R-XX:+UseNUMA); fi
}
