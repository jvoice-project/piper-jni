# piper-jni - Publish

Create a new release:

```shell
./mvnw release:prepare
./mvnw release:clean
```

Push the tag to GitHub:

```shell
git push origin main --follow-tags
```

GitHub Actions will publish the artifacts to Maven Central.
