/*
 * Copyright (C) 2023 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hedera.node.app.service.token.impl.test.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TOKEN_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TOKEN_IS_IMMUTABLE;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TOKEN_IS_PAUSED;
import static com.hedera.hapi.node.base.ResponseCodeEnum.TOKEN_WAS_DELETED;
import static com.hedera.node.app.spi.fixtures.Assertions.assertThrowsPreCheck;
import static com.hedera.node.app.spi.fixtures.workflows.ExceptionConditions.responseCode;
import static com.hedera.test.factories.scenarios.TokenDeleteScenarios.DELETE_WITH_KNOWN_TOKEN;
import static com.hedera.test.factories.scenarios.TokenDeleteScenarios.DELETE_WITH_MISSING_TOKEN;
import static com.hedera.test.factories.scenarios.TokenDeleteScenarios.DELETE_WITH_MISSING_TOKEN_ADMIN_KEY;
import static com.hedera.test.factories.scenarios.TxnHandlingScenario.TOKEN_ADMIN_KT;
import static com.hedera.test.factories.txns.SignedTxnFactory.DEFAULT_PAYER_KT;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.base.Key;
import com.hedera.hapi.node.base.TokenID;
import com.hedera.hapi.node.base.TransactionID;
import com.hedera.hapi.node.state.token.Token;
import com.hedera.hapi.node.token.TokenDeleteTransactionBody;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.node.app.service.token.ReadableTokenStore;
import com.hedera.node.app.service.token.impl.WritableAccountStore;
import com.hedera.node.app.service.token.impl.WritableTokenStore;
import com.hedera.node.app.service.token.impl.handlers.BaseCryptoHandler;
import com.hedera.node.app.service.token.impl.handlers.BaseTokenHandler;
import com.hedera.node.app.service.token.impl.handlers.TokenDeleteHandler;
import com.hedera.node.app.service.token.impl.test.handlers.util.ParityTestBase;
import com.hedera.node.app.service.token.impl.test.util.SigReqAdapterUtils;
import com.hedera.node.app.spi.fixtures.workflows.FakePreHandleContext;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.HandleException;
import com.hedera.node.app.spi.workflows.PreCheckException;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class TokenDeleteHandlerTest extends ParityTestBase {
    private static final AccountID ACCOUNT_1339 = BaseCryptoHandler.asAccount(1339);
    private static final TokenID TOKEN_987_ID = BaseTokenHandler.asToken(987L);

    private final TokenDeleteHandler subject = new TokenDeleteHandler();

    @Nested
    class PreHandleTests {
        @Test
        void noTokenThrowsError() {
            final var txn = newDissociateTxn(null);

            assertThatThrownBy(() -> subject.pureChecks(txn))
                    .isInstanceOf(PreCheckException.class)
                    .has(responseCode(INVALID_TOKEN_ID));
        }

        @Test
        void tokenDeletionWithValidTokenScenario() throws PreCheckException {
            final var theTxn = txnFrom(DELETE_WITH_KNOWN_TOKEN);

            final var context = new FakePreHandleContext(readableAccountStore, theTxn);
            context.registerStore(ReadableTokenStore.class, readableTokenStore);
            subject.preHandle(context);

            Assertions.assertThat(context.payerKey()).isEqualTo(DEFAULT_PAYER_KT.asPbjKey());
            Assertions.assertThat(context.requiredNonPayerKeys()).containsExactly(TOKEN_ADMIN_KT.asPbjKey());
        }

        @Test
        void tokenDeletionWithMissingTokenScenario() throws PreCheckException {
            final var theTxn = txnFrom(DELETE_WITH_MISSING_TOKEN);

            final var context = new FakePreHandleContext(readableAccountStore, theTxn);
            context.registerStore(ReadableTokenStore.class, readableTokenStore);
            assertThrowsPreCheck(() -> subject.preHandle(context), INVALID_TOKEN_ID);
        }

        @Test
        void tokenDeletionWithTokenWithoutAnAdminKeyScenario() throws PreCheckException {
            final var theTxn = txnFrom(DELETE_WITH_MISSING_TOKEN_ADMIN_KEY);

            final var context = new FakePreHandleContext(readableAccountStore, theTxn);
            context.registerStore(ReadableTokenStore.class, readableTokenStore);
            subject.preHandle(context);

            Assertions.assertThat(context.payerKey()).isEqualTo(DEFAULT_PAYER_KT.asPbjKey());
            Assertions.assertThat(context.requiredNonPayerKeys()).isEmpty();
        }
    }

    @Nested
    class HandleTests extends ParityTestBase {
        private WritableTokenStore writableTokenStore;

        @Test
        void rejectsNonexistingToken() {
            writableTokenStore = SigReqAdapterUtils.wellKnownWritableTokenStoreAt();

            final var context = mockContext();
            final var txn = newDissociateTxn(TOKEN_987_ID);
            given(context.body()).willReturn(txn);

            Assertions.assertThatThrownBy(() -> subject.handle(context))
                    .isInstanceOf(HandleException.class)
                    .has(responseCode(INVALID_TOKEN_ID));
        }

        @Test
        void rejectsDeletedToken() {
            // Create the token store with a deleted token
            writableTokenStore = newWritableStoreWithTokens(Token.newBuilder()
                    .tokenId(TOKEN_987_ID)
                    .deleted(true)
                    .adminKey(DEFAULT_PAYER_KT.asPbjKey())
                    .build());

            // Create the context and transaction
            final var context = mockContext();
            final var txn = newDissociateTxn(TOKEN_987_ID);
            given(context.body()).willReturn(txn);

            Assertions.assertThatThrownBy(() -> subject.handle(context))
                    .isInstanceOf(HandleException.class)
                    .has(responseCode(TOKEN_WAS_DELETED));
        }

        @Test
        void rejectsPausedToken() {
            // Create the token store with a paused token
            writableTokenStore = newWritableStoreWithTokens(Token.newBuilder()
                    .tokenId(TOKEN_987_ID)
                    .deleted(false)
                    .paused(true)
                    .adminKey(DEFAULT_PAYER_KT.asPbjKey())
                    .build());

            // Create the context and transaction
            final var context = mockContext();
            final var txn = newDissociateTxn(TOKEN_987_ID);
            given(context.body()).willReturn(txn);

            Assertions.assertThatThrownBy(() -> subject.handle(context))
                    .isInstanceOf(HandleException.class)
                    .has(responseCode(TOKEN_IS_PAUSED));
        }

        @Test
        void rejectsTokenWithoutAdminKey() {
            // Create the token store with a null admin key
            writableTokenStore = newWritableStoreWithTokens(Token.newBuilder()
                    .tokenId(TOKEN_987_ID)
                    .deleted(false)
                    .paused(false)
                    .adminKey((Key) null) // here's the null admin key
                    .build());

            // Create the context and transaction
            final var context = mockContext();
            final var txn = newDissociateTxn(TOKEN_987_ID);
            given(context.body()).willReturn(txn);

            Assertions.assertThatThrownBy(() -> subject.handle(context))
                    .isInstanceOf(HandleException.class)
                    .has(responseCode(TOKEN_IS_IMMUTABLE));
        }

        @Test
        void deletesValidToken() {
            // Verify that the treasury account's treasury titles count is correct before the test
            final var treasuryAcctId = BaseCryptoHandler.asAccount(3);
            final var treasuryAcct = writableAccountStore.get(treasuryAcctId);
            Assertions.assertThat(treasuryAcct.numberTreasuryTitles()).isEqualTo(2);

            // Create the writable token store
            writableTokenStore = SigReqAdapterUtils.wellKnownWritableTokenStoreAt();

            // Create the context and transaction
            final var context = mockContext();
            final var token535Id = BaseTokenHandler.asToken(535);
            final var txn = newDissociateTxn(token535Id);
            given(context.body()).willReturn(txn);

            // Run the subject's handle method
            subject.handle(context);

            // Verify the token was deleted
            final var deletedToken = writableTokenStore.get(token535Id);
            Assertions.assertThat(deletedToken.deleted()).isTrue();
            // Verify the token treasury account's treasury titles count was updated accordingly
            final var updatedTreasuryAcct = writableAccountStore.get(treasuryAcctId);
            Assertions.assertThat(updatedTreasuryAcct.numberTreasuryTitles()).isEqualTo(1);
        }

        private HandleContext mockContext() {
            final var context = mock(HandleContext.class);

            given(context.writableStore(WritableTokenStore.class)).willReturn(writableTokenStore);
            given(context.writableStore(WritableAccountStore.class)).willReturn(writableAccountStore);

            return context;
        }
    }

    private TransactionBody newDissociateTxn(TokenID token) {
        TokenDeleteTransactionBody.Builder deleteTokenTxnBodyBuilder = TokenDeleteTransactionBody.newBuilder();
        if (token != null) deleteTokenTxnBodyBuilder.token(token);
        return TransactionBody.newBuilder()
                .transactionID(
                        TransactionID.newBuilder().accountID(ACCOUNT_1339).build())
                .tokenDeletion(deleteTokenTxnBodyBuilder)
                .build();
    }
}
