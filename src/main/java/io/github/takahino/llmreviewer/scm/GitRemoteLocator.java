package io.github.takahino.llmreviewer.scm;

import org.eclipse.jgit.transport.CredentialsProvider;

/** git smart HTTPでのリモートURL規約と認証方式をプロバイダ毎に切り出す。 */
public interface GitRemoteLocator {

    String remoteUrl(String owner, String repoName);

    CredentialsProvider credentialsProvider();
}
