"""Coverage and freshness tests for the in-app licence catalogue.

`shared/src/commonMain/composeResources/files/licenses.json` is the file the app
actually serves.
The list is built from the **resolved runtime classpath** (see
`tools/licensing/runtime-classpath-*.txt`), not just the hand-declared
dependencies, so these tests fail if:

  * any runtime artifact has no entry (unattributed code shipped in the APK), or
  * the generator cannot resolve an artifact's licence, or
  * the committed asset drifted from what `collect.py` generates, or
  * a privacy locale drifted, or
  * an entry points at a licence text that is not embedded (or a URL instead), or
  * a shipped entry carries a prohibited licence (GPL/AGPL, non-commercial,
    share-alike, ...) or no licence at all (fail closed).

Run: python3 tools/licensing/collect_test.py
"""

import json
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import collect


def _asset():
    return json.loads(collect.ASSET.read_text(encoding="utf-8"))


class TestRuntimeCoverage(unittest.TestCase):
    def test_runtime_lock_is_present_and_non_trivial(self):
        coordinates = collect.runtime_coordinates()
        self.assertGreater(len(coordinates), 100, "runtime lock looks empty; run :androidApp:licensesRuntimeClasspath")

    def test_every_runtime_artifact_has_an_entry(self):
        component_ids = {c["id"] for c in _asset()["components"]}
        missing = sorted(c for c in collect.runtime_module_ids() if c not in component_ids)
        self.assertEqual(missing, [], f"runtime artifacts with no licence entry: {missing[:20]}")

    def test_every_runtime_artifact_resolves_its_licence(self):
        unresolved = collect.unresolved_dependencies()
        self.assertEqual(unresolved, [], f"runtime artifacts with no resolvable licence: {unresolved[:20]}")

    def test_transitive_google_runtime_is_attributed(self):
        # Regression: play-services enter transitively through Google sign-in and are shipped.
        ids = {c["id"] for c in _asset()["components"]}
        self.assertTrue(any(i.startswith("com.google.android.gms:play-services") for i in ids))

    def test_every_declared_dependency_has_an_entry(self):
        declared = collect.declared_dependency_ids()
        self.assertTrue(declared, "no dependencies parsed; the parser broke")
        component_ids = {c["id"] for c in _asset()["components"]}
        missing = sorted(d for d in declared if d not in component_ids)
        self.assertEqual(missing, [], f"declared dependencies with no licence entry: {missing}")

    def test_asset_is_in_lockstep_with_the_catalogue(self):
        self.assertEqual(
            _asset(),
            collect.build_asset(),
            "licenses.json is stale or hand-edited; run `python3 tools/licensing/collect.py`",
        )


class TestPrivacyLocales(unittest.TestCase):
    def test_bundled_privacy_matches_every_locale_source(self):
        for locale in collect.PRIVACY_SOURCES:
            with self.subTest(locale=locale):
                target = collect.privacy_asset(locale)
                self.assertTrue(target.is_file(), f"missing privacy asset for {locale}")
                self.assertEqual(
                    target.read_text(encoding="utf-8"),
                    collect.PRIVACY_SOURCES[locale].read_text(encoding="utf-8"),
                    f"privacy asset for {locale} drifted; run collect.py",
                )

    def test_all_app_locales_are_covered(self):
        self.assertEqual(set(collect.PRIVACY_SOURCES), {"en", "es", "fr", "de"})


class TestAssetIntegrity(unittest.TestCase):
    def test_every_component_resolves_its_licence_text_or_url(self):
        asset = _asset()
        for component in asset["components"]:
            with self.subTest(component=component["id"]):
                if component["licenseText"] is not None:
                    self.assertIn(component["licenseText"], asset["texts"])
                    self.assertTrue(asset["texts"][component["licenseText"]].strip())
                else:
                    self.assertTrue(
                        component["licenseTextUrl"] or component["notice"],
                        f"{component['id']} has neither embedded text, a URL nor a notice",
                    )

    def test_each_font_carries_its_own_ofl_copyright(self):
        by_id = {c["id"]: c for c in _asset()["components"]}
        asset = _asset()
        expected = {
            "font:BricolageGrotesque": "Bricolage Grotesque Project Authors",
            "font:PlusJakartaSans": "Plus Jakarta Sans Project Authors",
            "font:JetBrainsMono": "JetBrains Mono Project Authors",
        }
        for font_id, copyright_line in expected.items():
            text = asset["texts"][by_id[font_id]["licenseText"]]
            self.assertIn(copyright_line, text, f"{font_id} does not carry its own OFL")

    def test_google_sdks_are_not_mislabelled_as_apache(self):
        by_id = {c["id"]: c for c in _asset()["components"]}
        self.assertEqual(by_id["com.google.android.gms:play-services-auth"]["license"], "Android-SDK")
        self.assertNotIn("com.android.billingclient:billing", by_id)

    def test_distributed_media_and_model_are_present(self):
        ids = {c["id"] for c in _asset()["components"]}
        for required in (
            "model:wav2vec2-lv-60-espeak-cv-ft",
            "data:cmudict",
            "asset:catalogue-images",
            "asset:catalogue-audio",
            "font:BricolageGrotesque",
            "font:PlusJakartaSans",
            "font:JetBrainsMono",
        ):
            self.assertIn(required, ids)

    def test_no_catalogued_entry_is_unattributed(self):
        # "Zero unattributed, no asterisks": every entry in the shipped catalogue
        # resolves a licence, including server/test-only ones that never reach the APK.
        for component in _asset()["components"]:
            with self.subTest(component=component["id"]):
                self.assertTrue(component["license"], f"{component['id']} has no licence")
                self.assertNotEqual(component["license"], "unknown", f"{component['id']} has an unknown licence")

    def test_namespace_less_pom_is_parsed(self):
        # embedded-redis publishes a POM without the Maven 4.0.0 `xmlns`; the
        # collector used to skip it and leave the artifact unattributed.
        by_id = {c["id"]: c for c in collect.build_catalog()}
        entry = by_id.get("com.github.codemonstur:embedded-redis")
        self.assertIsNotNone(entry, "embedded-redis is not catalogued")
        self.assertEqual(entry["license"], "Apache-2.0")

    def test_server_and_test_dependencies_are_labelled_by_scope(self):
        # Regression: jedis (server main) and embedded-redis (server test) used to
        # be stamped as Android runtime deps and shown as such in the About screen.
        by_id = {c["id"]: c for c in _asset()["components"]}
        self.assertEqual(by_id["redis.clients:jedis"]["distribution"], "server")
        self.assertEqual(by_id["com.github.codemonstur:embedded-redis"]["distribution"], "test")
        self.assertNotIn("runtime", by_id["redis.clients:jedis"]["usage"])


class TestDependencyScopes(unittest.TestCase):
    def test_scopes_follow_the_build_file_and_the_verb(self):
        scopes = collect.declared_dependency_scopes()
        self.assertEqual(scopes["redis.clients:jedis"], "server")
        self.assertEqual(scopes["com.github.codemonstur:embedded-redis"], "test")
        self.assertEqual(scopes["io.ktor:ktor-server-netty"], "server")
        self.assertEqual(scopes["org.robolectric:robolectric"], "test")
        self.assertEqual(scopes["io.ktor:ktor-client-mock"], "test")

    def test_runtime_modules_are_always_app(self):
        # Curated rows state their own distribution on purpose (e.g. the Compose
        # BOM ships no code); only auto-resolved modules must follow the runtime.
        runtime = collect.runtime_module_ids()
        deliberate = set(collect.CURATED_DEPS) | {c["id"] for c in collect.COMPONENTS}
        by_id = {c["id"]: c for c in collect.build_catalog()}
        mislabelled = [
            m for m in runtime if m not in deliberate and by_id[m]["distribution"] != "app"
        ]
        self.assertEqual(mislabelled, [], f"runtime modules not labelled app: {mislabelled[:10]}")


def _injected_component(module_id, licence, license_name=None):
    return {
        "id": module_id,
        "name": module_id.split(":")[-1],
        "author": "Test",
        "license": licence,
        "licenseName": license_name or licence or "",
        "distribution": "app",
    }


class TestLicencePolicy(unittest.TestCase):
    """The gate is fail-closed: the current tree passes and an injected bad
    dependency fails, so a GPL/AGPL or non-commercial artifact cannot slip in.

    The bad cases are simulated here on purpose; no real dependency is touched.
    """

    def test_current_tree_passes_the_gate(self):
        blocking, _ = collect.gate_decision()
        self.assertEqual(blocking, [], f"current catalogue is blocked: {blocking}")

    def test_injected_gpl_dependency_fails_the_gate(self):
        bad = _injected_component("org.example:copyleft-lib", "GPL-3.0", "GNU General Public License v3.0")
        components = collect.build_catalog() + [bad]
        distributed = collect.distributed_component_ids() | {bad["id"]}
        blocking, _ = collect.gate_decision(components, distributed)
        self.assertIn(bad["id"], [v["id"] for v in blocking])

    def test_agpl_and_non_commercial_are_blocked_too(self):
        cases = (
            ("AGPL-3.0", "GNU Affero General Public License v3.0"),
            ("CC-BY-NC-4.0", "Creative Commons Attribution NonCommercial 4.0"),
            ("FLUX-Non-Commercial", "FLUX Non-Commercial License"),
        )
        for i, (licence, name) in enumerate(cases):
            with self.subTest(licence=licence):
                bad = _injected_component(f"org.example:bad-{i}", licence, name)
                components = collect.build_catalog() + [bad]
                distributed = collect.distributed_component_ids() | {bad["id"]}
                blocking, _ = collect.gate_decision(components, distributed)
                self.assertIn(bad["id"], [v["id"] for v in blocking])

    def test_unreadable_licence_fails_closed_when_distributed(self):
        bad = _injected_component("org.example:mystery-lib", "")
        components = collect.build_catalog() + [bad]
        distributed = collect.distributed_component_ids() | {bad["id"]}
        blocking, _ = collect.gate_decision(components, distributed)
        self.assertIn(bad["id"], [v["id"] for v in blocking])

    def test_unattributed_entry_is_blocking_even_when_not_distributed(self):
        bad = _injected_component("org.example:unattributed-lib", "")
        components = collect.build_catalog() + [bad]
        distributed = collect.distributed_component_ids()
        blocking, warnings = collect.gate_decision(components, distributed)
        self.assertIn(bad["id"], [v["id"] for v in blocking])
        self.assertNotIn(bad["id"], [v["id"] for v in warnings])

    def test_non_distributed_copyleft_warns_without_blocking(self):
        # A test/server-only copyleft dependency never enters the APK, so it is
        # reported but does not fail the build (coherent with the audit).
        bad = _injected_component("org.example:test-only-gpl", "GPL-2.0")
        components = collect.build_catalog() + [bad]
        distributed = collect.distributed_component_ids()
        blocking, warnings = collect.gate_decision(components, distributed)
        self.assertNotIn(bad["id"], [v["id"] for v in blocking])
        self.assertIn(bad["id"], [v["id"] for v in warnings])

    def test_allow_list_rejects_a_permissive_licence_that_was_not_reviewed(self):
        # Fail closed: a brand-new permissive licence still needs a deliberate
        # allow-list edit before it can ship.
        bad = _injected_component("org.example:new-licence", "WTFPL")
        components = collect.build_catalog() + [bad]
        distributed = collect.distributed_component_ids() | {bad["id"]}
        blocking, _ = collect.gate_decision(components, distributed)
        self.assertIn(bad["id"], [v["id"] for v in blocking])


if __name__ == "__main__":
    unittest.main()
