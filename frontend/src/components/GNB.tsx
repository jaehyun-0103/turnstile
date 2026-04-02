export const GNB = () => {
  const isLoggedIn = true;
  const mockUser = { name: "테스터", balance: 500000 };

  return (
    <header className="sticky top-0 z-50 w-full bg-white border-b border-gray-200 shadow-sm">
      <div className="max-w-7xl mx-auto px-4 h-16 flex items-center justify-between">
        {/* 로고 영역 */}
        <div className="text-2xl font-extrabold text-blue-600 cursor-pointer">Turnstile</div>

        {/* 우측 메뉴 영역 */}
        <div>
          {isLoggedIn ? (
            <div className="flex items-center gap-6">
              <div className="text-sm text-gray-600">
                <span className="font-semibold text-gray-800">{mockUser.name}</span> 님
                <span className="ml-4 text-blue-600 font-bold">{mockUser.balance.toLocaleString()} 원</span>
              </div>
              <button className="text-sm font-medium text-gray-500 hover:text-gray-800 transition">로그아웃</button>
            </div>
          ) : (
            <button className="px-4 py-2 text-sm font-semibold text-white bg-blue-600 rounded-lg hover:bg-blue-700 transition">
              로그인
            </button>
          )}
        </div>
      </div>
    </header>
  );
};
