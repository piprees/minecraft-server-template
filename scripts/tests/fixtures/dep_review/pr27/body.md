Bumps [actions/setup-java](https://github.com/actions/setup-java) from 5 to 6.
<details>
<summary>Release notes</summary>
<p><em>Sourced from <a href="https://github.com/actions/setup-java/releases">actions/setup-java's releases</a>.</em></p>
<blockquote>
<h2>v6.0.0</h2>
<h2>What's Changed</h2>
<ul>
<li>dist: Migrate from Zulu Discovery API to Azul Metadata API by <a href="https://github.com/jameswald"><code>@​jameswald</code></a> in <a href="https://redirect.github.com/actions/setup-java/pull/1010">actions/setup-java#1010</a></li>
<li>feat: add .mvn/extensions.xml to Maven cache key pattern by <a href="https://github.com/brunoborges"><code>@​brunoborges</code></a> with <a href="https://github.com/Copilot"><code>@​Copilot</code></a> in <a href="https://redirect.github.com/actions/setup-java/pull/1041">actions/setup-java#1041</a></li>
<li>Migrate to ESM and upgrade dependencies by <a href="https://github.com/priyagupta108"><code>@​priyagupta108</code></a> in <a href="https://redirect.github.com/actions/setup-java/pull/1078">actions/setup-java#1078</a></li>
<li>Map Zulu x86 architecture to i686 for Azul Metadata API by <a href="https://github.com/brunoborges"><code>@​brunoborges</code></a> in <a href="https://redirect.github.com/actions/setup-java/pull/1079">actions/setup-java#1079</a></li>
<li>Rename jdkFile input to jdk-file with deprecated alias by <a href="https://github.com/brunoborges"><code>@​brunoborges</code></a> in <a href="https://redirect.github.com/actions/setup-java/pull/1083">actions/setup-java#1083</a></li>
<li>Infer distribution from asdf .tool-versions vendor prefix by <a href="https://github.com/brunoborges"><code>@​brunoborges</code></a> in <a href="https://redirect.github.com/actions/setup-java/pull/1084">actions/setup-java#1084</a></li>
<li>Add Maven compiler problem matcher for javac diagnostics by <a href="https://github.com/brunoborges"><code>@​brunoborges</code></a> in <a href="https://redirect.github.com/actions/setup-java/pull/1086">actions/setup-java#1086</a></li>
<li>feat: expose cache-primary-key output (<a href="https://redirect.github.com/actions/setup-java/issues/597">#597</a>) by <a href="https://github.com/brunoborges"><code>@​brunoborges</code></a> in <a href="https://redirect.github.com/actions/setup-java/pull/1088">actions/setup-java#1088</a></li>
<li>docs: clarify V6 ESM migration is not a user-facing breaking change by <a href="https://github.com/brunoborges"><code>@​brunoborges</code></a> in <a href="https://redirect.github.com/actions/setup-java/pull/1090">actions/setup-java#1090</a></li>
<li>Support multi-field Java versions like <code>18.0.1.1</code> by <a href="https://github.com/brunoborges"><code>@​brunoborges</code></a> in <a href="https://redirect.github.com/actions/setup-java/pull/1092">actions/setup-java#1092</a></li>
<li>docs: document seeding the Maven cache for plugin dependencies by <a href="https://github.com/brunoborges"><code>@​brunoborges</code></a> in <a href="https://redirect.github.com/actions/setup-java/pull/1094">actions/setup-java#1094</a></li>
<li>docs: clarify Maven cache paths and key hash inputs by <a href="https://github.com/brunoborges"><code>@​brunoborges</code></a> in <a href="https://redirect.github.com/actions/setup-java/pull/1096">actions/setup-java#1096</a></li>
<li>Support pinning java-version as &quot;latest&quot; by <a href="https://github.com/brunoborges"><code>@​brunoborges</code></a> in <a href="https://redirect.github.com/actions/setup-java/pull/1093">actions/setup-java#1093</a></li>
<li>chore(deps-dev): bump eslint from 10.6.0 to 10.7.0 by <a href="https://github.com/dependabot"><code>@​dependabot</code></a>[bot] in <a href="https://redirect.github.com/actions/setup-java/pull/1101">actions/setup-java#1101</a></li>
<li>chore(deps-dev): bump eslint-plugin-n from 18.2.1 to 18.2.2 by <a href="https://github.com/dependabot"><code>@​dependabot</code></a>[bot] in <a href="https://redirect.github.com/actions/setup-java/pull/1103">actions/setup-java#110