import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

const source = readFileSync(new URL('../src/main/kotlin/graphharness/NavigationProfile.kt', import.meta.url), 'utf8');
const guidance = source.split('val codexInstructions = """')[1].split('""".trimIndent()')[0];
const blocks = [...guidance.matchAll(/```javascript\n([\s\S]*?)```/g)].map(match => match[1]);
const AsyncFunction = Object.getPrototypeOf(async function () {}).constructor;
const bundleName = 'mcp__graphharness__build_context_bundle';
const editName = 'mcp__graphharness__replace_node_body';

async function run(code, implementations) {
    const calls = [], output = [];
    const tools = Object.fromEntries(Object.entries(implementations).map(([name, implementation]) => [name, async arguments_ => {
        calls.push({ name, arguments_ });
        return implementation(arguments_);
    }]));
    await new AsyncFunction('ALL_TOOLS', 'tools', 'text', code)(
        Object.keys(implementations).map(name => ({ name })), tools, value => output.push(value),
    );
    return { calls, output };
}

test('discovery exposes the optional editor and emits structured context exactly once without writing', async () => {
    const context = { snapshot_id: 'snapshot', source_slices: [{ source: 'return 1;' }] };
    const actual = await run(blocks[0], {
        mcp__other__build_context_bundle: () => assert.fail('Unrelated server selected'),
        [bundleName]: () => ({ content: [{ type: 'text', text: 'duplicate copy' }], structuredContent: context }),
        [editName]: () => assert.fail('Discovery must not edit'),
    });
    assert.deepEqual(actual.calls.map(call => call.name), [bundleName]);
    assert.deepEqual(actual.output, [{ replace_node_body_available: true }, context]);
});

test('read-only discovery preserves content-only results and reports editor absence', async () => {
    const content = [{ type: 'text', text: 'source available without structured content' }];
    const actual = await run(blocks[0], { [bundleName]: () => ({ content }) });
    assert.deepEqual(actual.output, [{ replace_node_body_available: false }, content]);
});

test('missing tools produce a native fallback without attempting an invented call', async () => {
    const retrieval = await run(blocks[0], {});
    const editing = await run(blocks[1], {});
    assert.equal(retrieval.calls.length, 0);
    assert.equal(editing.calls.length, 0);
    assert.deepEqual(retrieval.output, [
        { replace_node_body_available: false }, 'GraphHarness bundle unavailable; use native inspection.',
    ]);
    assert.deepEqual(editing.output, ['GraphHarness node editing unavailable; use native editing.']);
});

test('both documented ambiguity routes pass the selected overload id as an argument', async () => {
    const candidates = [
        { id: 'method:example.Policy.check:int:boolean', file: 'Policy.java', signature: '(int) -> boolean' },
        { id: 'method:example.Policy.check:example.Request:boolean', file: 'Policy.java', signature: '(example.Request) -> boolean' },
    ];
    const candidate = candidates.find(item => item.file === 'Policy.java' && item.signature === '(example.Request) -> boolean');
    const expressions = [...guidance.matchAll(/\b(build_context_bundle|get_source)\(\{node_id: candidate\.id[^}]*\}\)/g)];
    assert.equal(expressions.length, 2);
    for (const [expression, name] of expressions) {
        const result = await new AsyncFunction(name, 'candidate', `return await ${expression};`)(arguments_ => {
            assert.equal(arguments_.node_id, candidate.id);
            assert.equal(arguments_.task, undefined);
            return { node_id: candidate.id, source: 'return request.ready();', snapshot_id: 'fresh' };
        }, candidate);
        assert.equal(result.node_id, candidate.id);
        assert.equal(result.snapshot_id, 'fresh');
    }
});

const values = {
    SELECTED_NODE_ID: 'method:example.Policy.check:example.Request:boolean',
    RETURNED_SNAPSHOT_ID: 'fresh-snapshot',
    RETURNED_FILE_HASH: 'f'.repeat(64),
    COMPLETE_REPLACEMENT_BODY_STATEMENTS: 'return request.ready();',
};

function editExample() {
    return blocks[1].replace(/"(SELECTED_NODE_ID|RETURNED_SNAPSHOT_ID|RETURNED_FILE_HASH|COMPLETE_REPLACEMENT_BODY_STATEMENTS)"/g,
        (_, key) => JSON.stringify(values[key]));
}

test('node-edit example forwards the fresh identity and complete body once and returns the actual receipt', async () => {
    const receipt = { committed: true, file_hash: 'a'.repeat(64), indexing: 'pending', project_tests_run: false };
    const actual = await run(editExample(), {
        [editName]: arguments_ => {
            assert.deepEqual(arguments_, {
                node_id: values.SELECTED_NODE_ID, snapshot_id: values.RETURNED_SNAPSHOT_ID,
                expected_file_hash: values.RETURNED_FILE_HASH, new_body: values.COMPLETE_REPLACEMENT_BODY_STATEMENTS,
            });
            return { structuredContent: receipt, content: [{ type: 'text', text: 'duplicate copy' }] };
        },
    });
    assert.equal(actual.calls.length, 1);
    assert.deepEqual(actual.output, [receipt]);
});

test('node-edit example preserves a rejection and does not retry or fabricate a commit', async () => {
    const rejection = { error: { code: 'stale_source', message: 'Source changed.' } };
    const actual = await run(editExample(), {
        [editName]: () => ({ isError: true, structuredContent: rejection, content: [{ type: 'text', text: 'Source changed.' }] }),
    });
    assert.equal(actual.calls.length, 1);
    assert.deepEqual(actual.output, [rejection]);
});
