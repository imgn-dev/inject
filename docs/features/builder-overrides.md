# Builder Overrides

## The problem

The graph that `ServiceLoader` discovers is the production graph. A test — or
a `main` method wiring a simulation — often needs that exact graph with a few
bindings replaced: the real gateway swapped for a recording fake, a clock
pinned to a fixed instant. Editing the beans to make them swappable would
pollute production code for the test's benefit.

## How it solves it

`Injector.builder()` creates an injector from the same discovered factories,
with explicit bindings that **shadow** any discovered binding for the same
key. Overrides win silently — no duplicate check — because replacing is their
purpose:

```java
Injector injector = Injector.builder()
        .bind(Clock.class, Clock.fixed(instant, ZoneOffset.UTC))
        .bind(Key.of(String.class, Names.named("greeting")), "Hi")
        .bindProvider(Gateway.class, RecordingGateway::new)
        .build();
```

Everything downstream sees the override: any bean depending on `Clock`
receives the fixed one, with the rest of the graph untouched. That includes
resolutions through a [child injector](child-injectors.md), which never
rebuilds the discovered bean an override replaced.

- `bind(...)` — a fixed instance, returned for every resolution.
- `bindProvider(...)` — a provider consulted on every resolution.

The same pair of operations exists on
[`childBuilder()`](child-injectors.md); the difference is lifecycle. Builder
overrides bake replacement into a standalone injector at creation; a child
layers bindings over a live parent.

## Limitations

- Overrides replace whole bindings by key. They cannot wrap or decorate the
  discovered binding, and there is no way to reach the shadowed original
  through the same injector.
- Framework keys (`Injector`, `Context`, `Factory`) are not bindable.
- An override is raw: no scoping, no member injection, no
  [`BeanDefinition`](injector-injection.md). A `bindProvider` provider that
  should cache must cache itself.
- Overrides shadow silently — binding a key nothing uses is not an error, so a
  typo in a `Key` yields an unused override rather than a diagnostic.
